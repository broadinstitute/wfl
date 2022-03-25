(ns wfl.executor
  (:require [clojure.edn           :as edn]
            [clojure.set           :as set :refer [rename-keys]]
            [clojure.spec.alpha    :as s]
            [clojure.string        :as str]
            [ring.util.codec       :refer [url-encode]]
            [wfl.api.workloads     :refer [defoverload] :as workloads]
            [wfl.environment       :as env]
            [wfl.jdbc              :as jdbc]
            [wfl.log               :as log]
            [wfl.module.all        :as all]
            [wfl.service.cromwell  :as cromwell]
            [wfl.service.datarepo  :as datarepo]
            [wfl.service.dockstore :as dockstore]
            [wfl.service.firecloud :as firecloud]
            [wfl.service.postgres  :as postgres]
            [wfl.service.rawls     :as rawls]
            [wfl.service.slack     :as slack]
            [wfl.stage             :as stage :refer [log-prefix]]
            [wfl.util              :as util :refer [map-keys utc-now]])
  (:import [wfl.util UserException]))

;; executor operations
(defmulti update-executor!
  "Consume items from the `workload`'s source queue and enqueue to its executor
   queue for consumption by a later processing stage,
   performing any external effects as necessary.
   Implementations should avoid maintaining in-memory state and making long-
   running external calls, favouring internal queues to manage such tasks
   asynchronously between invocations.  Note that the `Workload`'s Source queue and Executor
   are parameterised types and the Source queue's parameterisation must be
   convertible to the Executor's."
  (fn [{:keys [executor] :as _workload}] (:type executor)))

(defmulti executor-workflows
  "Use db `transaction` to return the workflows created by the `executor`
  matching optional `filters` (ex. status, submission)."
  (fn [_transaction executor _filters] (:type executor)))

(defmulti executor-throw-if-invalid-retry-filters
  "Throw if `filters` are invalid for `workload`'s retry request."
  (fn [{:keys [executor] :as _workload} _filters] (:type executor)))

(defmulti executor-retry-workflows!
  "Retry/resubmit the `workflows` managed by the `executor`."
  (fn [{:keys [executor] :as _workload} _workflows] (:type executor)))

;; load/save operations
(defmulti create-executor!
  "Create an `Executor` instance using the database `transaction` and
   configuration in the executor `request` and return a
   `[type items]` pair to be written to a workload record as
   `executor_type` and `executor_items`.
   Notes:
   - This is a factory method registered for workload creation.
   - The `Executor` type string must match a value of the `executor` enum
     in the database schema.
   - This multimethod is type-dispatched on the `:name` association in the
     `request`."
  (fn [_transaction _workload-id request] (:name request)))

(defmulti load-executor!
  "Return the `Executor` implementation associated with the `executor_type` and
   `executor_items` fields of the `workload` row in the database.
   Note that this multimethod is type-dispatched on the `:executor_type`
   association in the `workload`."
  (fn [_transaction workload] (:executor_type workload)))

(defmethod create-executor! :default
  [_ _ {:keys [name] :as request}]
  (throw (UserException.
          "Invalid executor name"
          {:name      name
           :request   request
           :status    400
           :executors (-> create-executor! methods (dissoc :default) keys)})))

;; Terra Executor
(def ^:private ^:const terra-executor-name  "Terra")
(def ^:private ^:const terra-executor-type  "TerraExecutor")
(def ^:private ^:const terra-executor-table "TerraExecutor")
(def ^:private ^:const terra-executor-serialized-fields
  {:workspace                  :workspace
   :methodConfiguration        :method_configuration
   :methodConfigurationVersion :method_configuration_version
   :fromSource                 :from_source})

;; Specs
(s/def ::entity ::all/uuid)
(s/def ::fromSource string?)
(s/def ::methodConfiguration (s/and string? util/terra-namespaced-name?))
(s/def ::methodConfigurationVersion integer?)
(s/def ::reference ::all/uuid)
(s/def ::submission ::all/uuid)
(s/def ::workflow ::all/uuid)

(s/def ::terra-executor (s/keys :req-un [::all/name
                                         ::fromSource
                                         ::methodConfiguration
                                         ::all/workspace]
                                :opt-un [::methodConfigurationVersion]))

(s/def ::terra-executor-workflow (s/keys :req-un [::entity
                                                  ::methodConfiguration
                                                  ::reference
                                                  ::submission
                                                  ::all/workspace]
                                         :opt-un [::workflow
                                                  ::all/status]))

(defn ^:private write-terra-executor [tx id executor]
  (let [create  "CREATE TABLE %s OF TerraExecutorDetails (PRIMARY KEY (id))"
        alter   "ALTER TABLE %s ALTER COLUMN id ADD GENERATED ALWAYS AS IDENTITY"
        details (format "%s_%09d" terra-executor-type id)]
    (jdbc/db-do-commands tx [(format create details) (format alter details)])
    [terra-executor-type
     (-> (select-keys executor (keys terra-executor-serialized-fields))
         (update :fromSource pr-str)
         (set/rename-keys terra-executor-serialized-fields)
         (assoc :details details)
         (->> (jdbc/insert! tx terra-executor-table) first :id str))]))

(defn ^:private load-terra-executor [tx {:keys [executor_items] :as workload}]
  (if-let [id (util/parse-int executor_items)]
    (-> (postgres/load-record-by-id! tx terra-executor-table id)
        (assoc :type terra-executor-type)
        (set/rename-keys (set/map-invert terra-executor-serialized-fields))
        (update :fromSource edn/read-string))
    (throw (ex-info "Invalid executor_items" {:workload workload}))))

(defn ^:private throw-unless-dockstore-method
  "Throw unless the `sourceRepo` of `methodRepoMethod` is \"dockstore\"."
  [{:keys [sourceRepo] :as methodRepoMethod}]
  (when-not (= "dockstore" sourceRepo)
    (throw (UserException. "Only Dockstore methods are supported."
                           {:status 400 :methodRepoMethod methodRepoMethod}))))

(defn ^:private terra-executor-validate-request-or-throw
  "Verify existence and availability of `workspace` and `methodConfiguration`."
  [{:keys [workspace methodConfiguration fromSource] :as executor}]
  (firecloud/workspace-or-throw workspace)
  (when-not (= "importSnapshot" fromSource)
    (throw
     (UserException. "Unsupported coercion" (util/make-map fromSource))))
  (let [{:keys [methodRepoMethod methodConfigVersion]}
        (firecloud/method-configuration workspace methodConfiguration)]
    (throw-unless-dockstore-method methodRepoMethod)
    (assoc executor :methodConfigurationVersion methodConfigVersion)))

(defn ^:private entity-from-snapshot
  "Coerce the `snapshot` into a workspace entity via `executor` fromSource."
  [{:keys [executor] :as workload} {:keys [id name] :as snapshot}]
  (let [{:keys [workspace fromSource]} executor]
    (when-not (= "importSnapshot" fromSource)
      (throw (ex-info "Unknown conversion from snapshot to workspace entity."
                      {:executor executor
                       :snapshot snapshot})))
    (log/debug "Creating or getting snapshot reference"
               :workload              (workloads/to-log workload)
               :snapshotReferenceName name
               :workspace             workspace)
    (rawls/create-or-get-snapshot-reference workspace id name)))

(defn ^:private from-source
  "Coerce `object` to form understood by `executor``."
  [{:keys [executor] :as workload} [type value :as object]]
  (case type
    :datarepo/snapshot (entity-from-snapshot workload value)
    (throw (ex-info "No method to coerce object into workspace entity"
                    {:executor executor
                     :object   object}))))

(defn ^:private create-user-comment
  "Create a user comment to be added to an executor submission."
  [note workload snapshot]
  (str/join \space [note
                    "Workload:"    (:uuid workload)
                    "Snapshot ID:" (:id snapshot)]))

(defn ^:private warn-on-method-config-version-mismatch
  "Log warning if `methodConfigVersion` does not match that of `executor`."
  [{:keys [executor]            :as workload}
   {:keys [methodConfigVersion] :as methodconfig}]
  (let [expected (:methodConfigurationVersion executor)]
    (when-not (= expected methodConfigVersion)
      (log/warning "Unexpected method configuration version"
                   :workload            (workloads/to-log workload)
                   :expected            expected
                   :methodConfiguration methodconfig))))

(defn ^:private table-from-snapshot
  "Return singular table in `snapshot`, or log error."
  [workload {:keys [tables] :as snapshot}]
  (let [[table & rest] (map :name tables)]
    (if (and table (empty? rest))
      table
      (log/error "Expected exactly one table in snapshot"
                 :workload (workloads/to-log workload)
                 :snapshot snapshot))))

(defn ^:private update-method-configuration!
  "Update `methodConfiguration` in `workspace` with snapshot `reference` name
  and table as its root entity via Firecloud.
  Update executor table record ID with incremented `methodConfigurationVersion`."
  [{{:keys [id
            workspace
            methodConfiguration]} :executor :as workload}
   reference
   snapshot]
  (let [name    (get-in reference [:metadata :name])
        current (firecloud/method-configuration workspace methodConfiguration)
        _       (warn-on-method-config-version-mismatch workload current)
        inc'd   (inc (:methodConfigVersion current))
        newMc   (merge
                 current
                 {:dataReferenceName name :methodConfigVersion inc'd}
                 (when-let [table (table-from-snapshot workload snapshot)]
                   {:rootEntityType table}))]
    (log/debug "Updating method configuration"
               :workload               (workloads/to-log workload)
               :newMethodConfiguration newMc)
    (firecloud/update-method-configuration workspace methodConfiguration newMc)
    (jdbc/with-db-transaction [tx (postgres/wfl-db-config)]
      (jdbc/update! tx terra-executor-table
                    {:method_configuration_version inc'd}
                    ["id = ?" id]))))

(defn ^:private submission-created-slack-msg
  "Return a mrkdwn Slack message to be emitted for newly created `submission`."
  [{:keys [workspace]    :as _executor}
   {:keys [submissionId] :as _submission}
   snapshot]
  (let [emoji           (cromwell/emoji "Submitted")
        submission-link (-> submissionId
                            (firecloud/submission-url workspace)
                            (slack/link submissionId))
        snapshot-link   (-> snapshot
                            datarepo/snapshot-url
                            (slack/link (:name snapshot)))]
    (str/join \newline
              [(format "*%s Launched submission %s*" emoji submission-link)
               (format "Snapshot %s" snapshot-link)])))

(defn ^:private create-submission!
  "Update `executor` method configuration to use `reference`,
  then submit in `executor` workspace via Firecloud.
  Notify Slack watchers and return submission."
  ([{:keys [executor] :as workload}
    user-comment-note
    reference
    [_type snapshot   :as _source-object]]
   (let [{:keys [workspace methodConfiguration]} executor]
     (update-method-configuration! workload reference snapshot)
     (log/debug "Initiating Terra submission"
                :workload            (workloads/to-log workload)
                :methodConfiguration methodConfiguration
                :workspace           workspace)
     (let [userComment
           (create-user-comment user-comment-note workload snapshot)
           submission
           (firecloud/submit-method workspace methodConfiguration userComment)
           message
           (submission-created-slack-msg executor submission snapshot)]
       (slack/notify-watchers workload message)
       submission)))
  ([workload user-comment-note reference]
   (->> (get-in reference [:attributes :snapshot])
        datarepo/snapshot
        (conj [:datarepo/snapshot])
        (create-submission! workload user-comment-note reference))))

(defn ^:private allocate-submission
  "Write or allocate workflow records for `submission`
  in `executor` details table, and return them."
  [{:keys [executor] :as workload}
   reference
   {:keys [submissionId workflows] :as _submission}]
  (log/debug "Allocating workflow records for submission"
             :workload     (workloads/to-log workload)
             :numWorkflows (count workflows)
             :submissionId submissionId)
  (letfn [(to-row [now {:keys [status workflowId entityName] :as _workflow}]
            {:reference  (get-in reference [:metadata :resourceId])
             :submission submissionId
             :workflow   workflowId
             :entity     entityName
             :status     status
             :updated    now})]
    (jdbc/with-db-transaction [tx (postgres/wfl-db-config)]
      (->> workflows
           (map (partial to-row (utc-now)))
           (jdbc/insert-multi! tx (:details executor))))))

(defn ^:private workflow-finished-slack-msg
  "Return a mrkdwn Slack message to be emitted for finished `workflow`s."
  [{:keys [workspace]                  :as _executor}
   {:keys [submission workflow status] :as _record}]
  (let [emoji           (cromwell/emoji status)
        workflow-link   (-> workflow
                            firecloud/workflow-url
                            (slack/link workflow))
        submission-link (-> submission
                            (firecloud/submission-url workspace)
                            (slack/link submission))]
    (str/join \newline
              [(format "*%s Workflow %s %s*" emoji workflow-link status)
               (format "Submission *%s*" submission-link)])))

;; Call this only while updating active workflows
;; to avoid repeated notifications for the same workflow's completion.
;;
(defn ^:private notify-on-workflow-completion
  "Notify `workload` watchers of any newly terminated workflows
  in executor details `records`.
  Return `records` for downstream processing."
  [{:keys [executor] :as workload} records]
  (->> records
       (filter #(cromwell/final? (:status %)))
       (mapv #(workflow-finished-slack-msg executor %))
       (run! #(slack/notify-watchers workload %)))
  records)

;; Workflows in newly created Firecloud submissions
;; may not have been scheduled yet
;; and thus do not have workflow UUIDs.
;; Thus, we first "allocate" the workflow in the database
;; and then later associate their workflow UUIDs.
;;
(defn ^:private update-unassigned-workflow-uuids!
  "Assign workflow uuids from previous submissions"
  [{{:keys [workspace details] :as executor} :executor :as workload}]
  (letfn [(read-a-submission-without-workflows []
            (let [query (str/join \space ["SELECT id, submission, entity FROM %s"
                                          "WHERE submission IN"
                                          "(SELECT submission FROM %s"
                                          "WHERE workflow IS NULL"
                                          "LIMIT 1)"])]
              (jdbc/with-db-transaction [tx (postgres/wfl-db-config)]
                (let [records (jdbc/query tx (format query details details))]
                  (when-first [{:keys [submission]} records]
                    [submission records])))))
          (zip-record [{:keys [entity] :as record}
                       {:keys [workflowId workflowEntity status]}]
            (when-not (= entity workflowEntity)
              (throw (ex-info "Failed to write workflow uuid: entity did not match"
                              {:expected entity
                               :actual   workflowEntity
                               :executor executor})))
            (assoc record :workflow workflowId :status status))
          (write-workflow-statuses [now records]
            (jdbc/with-db-transaction [tx (postgres/wfl-db-config)]
              (run!
               (fn [{:keys [id workflow status]}]
                 (jdbc/update! tx details
                               {:status status :workflow workflow :updated now}
                               ["id = ?" id]))
               records)))]
    (when-let [[submission records] (read-a-submission-without-workflows)]
      (let [{:keys [workflows]} (firecloud/get-submission workspace submission)]
        (when-not (== (count records) (count workflows))
          (throw (ex-info "Allocated more records than created workflows"
                          {:submission submission :executor executor})))
        (log/debug "Writing workflow UUIDs and statuses for submission"
                   :workload     (workloads/to-log workload)
                   :numWorkflows (count workflows)
                   :submissionId submission)
        (->> workflows
             (map #(update % :workflowEntity :entityName))
             (sort-by :workflowEntity)
             (map zip-record (sort-by :entity records))
             (notify-on-workflow-completion workload)
             (write-workflow-statuses (utc-now)))))))

(def ^:private active-workflows-query-template
  "Query template for fetching active workflows
  from an executor details table to be specified at runtime."
  (let [final-statuses
        (util/to-quoted-comma-separated-list rawls/final-statuses)]
    (str/join \space ["SELECT * FROM %s"
                      "WHERE submission IS NOT NULL"
                      "AND workflow IS NOT NULL"
                      "AND status NOT IN"
                      final-statuses
                      "ORDER BY id ASC"])))

(defn ^:private update-terra-workflow-statuses!
  "Update statuses in `details` table for active `workspace` workflows."
  [{{:keys [workspace details]} :executor :as workload}]
  (letfn [(update-status-from-firecloud
            [{:keys [submission workflow] :as record}]
            (->> (firecloud/get-workflow workspace submission workflow "status")
                 :status
                 (assoc record :status)))
          (write-workflow-statuses [now records]
            (jdbc/with-db-transaction [tx (postgres/wfl-db-config)]
              (run!
               (fn [{:keys [id status] :as _record}]
                 (jdbc/update! tx details {:status status :updated now}
                               ["id = ?" id]))
               (sort-by :entity records))))]
    (log/debug "Updating statuses for active workflows"
               :workload (workloads/to-log workload))
    (->> (jdbc/with-db-transaction [tx (postgres/wfl-db-config)]
           (jdbc/query tx (format active-workflows-query-template details)))
         (mapv update-status-from-firecloud)
         (notify-on-workflow-completion workload)
         (write-workflow-statuses (utc-now)))))

(defn ^:private update-terra-executor
  "Create new submission from new `source` snapshot if available,
  writing its workflows to `details` table.
  Update statuses for active or failed workflows in `details` table.
  Return updated `workload`."
  [{:keys [source _executor] :as workload}]
  (when-let [object (stage/peek-queue source)]
    (let [entity     (from-source workload object)
          submission (create-submission! workload "New submission" entity object)]
      (allocate-submission workload entity submission)
      (stage/pop-queue! source)))
  (update-unassigned-workflow-uuids! workload)
  (update-terra-workflow-statuses! workload)
  (jdbc/with-db-transaction [tx (postgres/wfl-db-config)]
    (workloads/load-workload-for-uuid tx (:uuid workload))))

(defn ^:private combine-record-workflow-and-outputs
  [{:keys [updated] :as _record}
   {:keys [workflowName] :as workflow}
   firecloud-outputs]
  (let [prefix  (str workflowName ".")
        outputs (-> firecloud-outputs
                    (get-in [:tasks (keyword workflowName) :outputs])
                    (util/unprefix-keys prefix))]
    (-> (assoc workflow :updated updated :outputs outputs)
        (update :inputs #(util/unprefix-keys % prefix))
        (set/rename-keys {:id :uuid})
        (util/select-non-nil-keys [:inputs :uuid :status :outputs :updated]))))

(defn ^:private peek-terra-executor-details
  "Get first unconsumed successful workflow record from `details` table."
  [{:keys [details] :as _executor}]
  (let [query (str/join \space ["SELECT * FROM %s"
                                "WHERE consumed IS NULL"
                                "AND status = 'Succeeded'"
                                "ORDER BY id ASC"
                                "LIMIT 1"])]
    (jdbc/with-db-transaction [tx (postgres/wfl-db-config)]
      (->> (format query details)
           (jdbc/query tx)
           first))))

(defn ^:private method-content-url
  "Return the URL to the WDL content used by this `methodRepoMethod`. If the WDL
   is hosted on GitHub, returns a jsdelivr URL to avoid GitHub rate limits."
  [{:keys [methodPath methodVersion] :as methodRepoMethod}]
  (throw-unless-dockstore-method methodRepoMethod)
  (let [id  (url-encode (str "#workflow/" methodPath))
        url (:url (dockstore/ga4gh-tool-descriptor id methodVersion "WDL"))]
    (if-let [groups (re-find #"^https://raw.githubusercontent.com/(.*)$" url)]
      (let [[author repo version path] (str/split (second groups) #"/" 4)]
        (str/join "/" ["https://cdn.jsdelivr.net/gh" author (str repo "@" version) path]))
      url)))

;; visible for testing
(defn describe-method
  "Describe the method associated with the `methodconfig`."
  [workspace methodconfig]
  (-> (firecloud/method-configuration workspace methodconfig)
      :methodRepoMethod
      method-content-url
      firecloud/describe-workflow))

(defn ^:private peek-terra-executor-queue
  "Get first unconsumed successful workflow from `executor` queue."
  [{:keys [workspace methodConfiguration] :as executor}]
  (when-let [{:keys [submission workflow] :as record}
             (peek-terra-executor-details executor)]
    [(describe-method workspace methodConfiguration)
     (combine-record-workflow-and-outputs
      record
      (firecloud/get-workflow workspace submission workflow)
      (firecloud/get-workflow-outputs workspace submission workflow))]))

(defn ^:private pop-terra-executor-queue
  "Consume first unconsumed successful workflow record in `details` table,
  or throw if none."
  [{:keys [details] :as executor}]
  (if-let [{:keys [id] :as _record} (peek-terra-executor-details executor)]
    (jdbc/with-db-transaction [tx (postgres/wfl-db-config)]
      (let [now (utc-now)]
        (jdbc/update! tx details {:consumed now :updated now} ["id = ?" id])))
    (throw (ex-info "No successful workflows in queue" {:executor executor}))))

(defn ^:private terra-executor-queue-length
  "Return the number of workflows in the `executor` queue that are yet to be
   consumed."
  [{:keys [details] :as _executor}]
  (let [query (str/join \space ["SELECT COUNT(*) FROM %s"
                                "WHERE consumed IS NULL"
                                "AND (status IS NULL OR"
                                "status NOT IN ('Failed', 'Aborted'))"])]
    (jdbc/with-db-transaction [tx (postgres/wfl-db-config)]
      (->> (format query details)
           (jdbc/query tx)
           first
           :count))))

;; terra-executor-workflows does not return workflows
;; that are being or have been retried. Why?
;;
;; TL/DR: It's simpler maybe?
;;
;; The truth is I'm not sure of a *good* reason to do this.
;;
;; Some context/terminology:
;; Workflows are be serialised to the DB as a kind of flattened list of lists,
;; where there `HEAD` of each inner list is the most recent workflow attempt and
;; the `TAIL` of each inner list are all those previous attempts.
;;
;; In the DB, this is implemented such that each `HEAD` element does not have a
;; "pointer" to a another attempt and each retried workflow has a "pointer" to
;; the workflow that retied it (a linked list with the pointers reversed - think
;; git commit trees):
;;
;;     Attempt0 -> Attempt1 -> ... -> HEAD
;;
;; My initial thought was that when getting workflows, you'd only want to see
;; the workflows at the `HEAD` (i.e. the most recent attempts at converting
;; inputs -> outputs). This would also make it harder to retry a workflow that
;; had already been retried*. This also drove the implementation making it
;; easier to find the HEAD of each list.
;;
;; Two alternatives come to mind:
;; - return the list of lists (though this would break hOps's retry code).
;; - include a {"retried": uuid} attribute in each workflow such that clients
;;   could reconstruct the graph of retries.
;;
;; I lean towards 2 because it might expose a richer API for clients to gather
;; information from. The difficulty is that when workflows are initially
;; retried, they're not assigned a workflow UUID yet so users might see that
;; a workflow does not have a {"retried": uuid} immediately after they've tried
;; to retry it.**
;;
;; * It would still be possible and we'd have to come up with a strategy for
;; handling this - perhaps traverse the list and retry the workflow if there's
;; not already an active workflow at the `HEAD`.
;;
;; ** Admittedly, this is still a problem with this design.
(defn ^:private terra-executor-workflows-sql-params
  "Return sql and params that query `details` for non-retried workflows
  matching `submission` and/or `status` if specified."
  [{:keys [details] :as _executor} {:keys [submission status] :as _filters}]
  (let [query (util/remove-empty-and-join
               ["SELECT * FROM %s"
                "WHERE workflow IS NOT NULL"
                "AND retry IS NULL"
                (when submission "AND submission = ?")
                (when status "AND status = ?")
                "ORDER BY id ASC"])]
    (->> [submission status]
         (concat [(format query details)])
         (remove nil?))))

(defn ^:private terra-executor-workflows
  "Return all the non-retried workflows executed by `executor`
  matching specified `filters`."
  [tx {:keys [details] :as executor} filters]
  (postgres/throw-unless-table-exists tx details)
  (let [sql-params      (terra-executor-workflows-sql-params executor filters)
        executor-subset (select-keys executor [:workspace :methodConfiguration])]
    (map #(merge executor-subset %) (jdbc/query tx sql-params))))

;; 1. Presently Rawls doesn't support submitting a subset of a snapshot:
;;    If we wish to retry 1+ workflows stemming from a snapshot,
;;    we must resubmit the snapshot in its entirety.
;; 2. Though we are technically resubmitting snapshot references
;;    and not submissions, this query takes a submission-based approach.
;;    Why? Because there is no ambiguity when linking a submission
;;    to its workflows.  With the capacity for retries, one reference
;;    may map to multiple sets of workflows, and retrying the reference
;;    should only update the sibling workflow records of those supplied.
;;
(defn ^:private workflow-and-sibling-records
  "Return the workflow records for all workflows in submissions
  associated with the specified `workflows` --
  i.e. records for `workflows` and their siblings."
  [tx {:keys [executor] :as workload} workflows]
  (let [details      (:details executor)
        _            (postgres/throw-unless-table-exists tx details)
        workflow-ids (util/to-quoted-comma-separated-list
                      (map :workflow workflows))
        query        (str/join \space ["SELECT * FROM %s"
                                       "WHERE submission IN"
                                       "(SELECT DISTINCT submission FROM %s"
                                       "WHERE workflow IN %s)"])]
    (log/debug "Fetching workflow-ids' sibling workflow records"
               :workload     (workloads/to-log workload)
               :workflow-ids workflow-ids)
    (jdbc/query tx (format query details details workflow-ids))))

(defn update-retried-workflow-records
  "Match `original-records` with `retry-records` on entity uuid,
  update each original record to point to its retry record,
  and propagate all updates to `details` table."
  [{:keys [executor] :as workload} [retry-records original-records]]
  (letfn [(link-retry-to-original
            [{:keys [entity]         :as original}
             {:keys [retryEntity id] :as retry}]
            (when-not (= entity retryEntity)
              (throw (ex-info (str "Failed to link retry to original workflow: "
                                   "mismatched entities")
                              {:original original
                               :retry    retry
                               :executor executor})))
            (assoc original :retry id))
          (write-retries
            [now records]
            (jdbc/with-db-transaction [tx (postgres/wfl-db-config)]
              (run!
               (fn [{:keys [id retry]}]
                 (jdbc/update! tx (:details executor)
                               {:retry retry :updated now}
                               ["id = ?" id]))
               records)))]
    (when-not (== (count retry-records) (count original-records))
      (throw (ex-info "All original records should be retried"
                      {:retry-records    retry-records
                       :original-records original-records
                       :executor         executor})))
    (log/debug "Linking retry records to their originals"
               :workload        (workloads/to-log workload)
               :numRetryRecords (count retry-records))
    (->> retry-records
         (map #(rename-keys % {:entity :retryEntity}))
         (sort-by :retryEntity)
         (map link-retry-to-original (sort-by :entity original-records))
         (write-retries (utc-now)))))

;; Visible for testing
(def retry-invalid-submission-error-message
  "Missing or invalid Terra Workspace submission ID")

(defn ^:private retry-submission-validation-error
  "Return error information to throw if Terra `submission` ID
  is unspecified or an invalid format."
  [submission]
  (when-not (s/valid? ::submission submission)
    {:message retry-invalid-submission-error-message}))

;; Visible for testing
(def retry-unsupported-status-error-message
  "Retry unsupported for requested workflow status")

(defn ^:private retry-status-validation-error
  "Return error information to throw if workflow `status`
  is specified and unretriable."
  [status]
  (when status
    (when-not (cromwell/retry-status? status)
      {:message retry-unsupported-status-error-message
       :data    {:supported-statuses cromwell/retry-status?}})))

;; Visible for testing
(def terra-executor-retry-filters-invalid-error-message
  "Cannot retry workload: invalid workflow filters.")

(defn ^:private terra-executor-throw-if-invalid-retry-filters
  "Throw if `submission` or `status` are invalid filters
  for workload `uuid`'s retry request."
  [{:keys [uuid] :as _workload} {:keys [submission status] :as filters}]
  (let [submission-error (retry-submission-validation-error submission)
        status-error     (retry-status-validation-error status)
        errors           (remove nil? [submission-error status-error])]
    (when (seq errors)
      (throw (UserException. terra-executor-retry-filters-invalid-error-message
                             (merge {:workload          uuid
                                     :filters           filters
                                     :validation-errors (map :message errors)
                                     :status            400}
                                    (into {} (map :data errors))))))))

;; Further work required to deal in generic entities
;; rather than assumed snapshot references:
;; https://broadinstitute.atlassian.net/browse/GH-1422
;;
(defn ^:private terra-executor-retry-workflows
  "Resubmit the snapshot references associated with `workflows` in `workspace`
  and update each original workflow record with the row ID of its retry."
  [{{:keys [workspace] :as executor} :executor :as workload} workflows]
  (letfn [(submit-reference [reference-id]
            (let [reference (rawls/get-snapshot-reference workspace reference-id)]
              (->> reference
                   (create-submission! workload "Retry")
                   (allocate-submission workload reference))))]
    (->> (jdbc/with-db-transaction [tx (postgres/wfl-db-config)]
           (workflow-and-sibling-records tx workload workflows))
         (group-by :reference)
         (map-keys submit-reference)
         (run! (partial update-retried-workflow-records workload)))))

(defn ^:private terra-executor-done? [executor]
  (zero? (stage/queue-length executor)))

(defn ^:private terra-executor-to-edn [executor]
  (-> executor
      (util/select-non-nil-keys (keys terra-executor-serialized-fields))
      (assoc :name terra-executor-name)))

(defmethod create-executor! terra-executor-name
  [tx id {:keys [skipValidation] :as request}]
  (let [executor (if skipValidation
                   request
                   (terra-executor-validate-request-or-throw request))]
    (write-terra-executor tx id executor)))

(defoverload load-executor!                terra-executor-type load-terra-executor)
(defoverload update-executor!              terra-executor-type update-terra-executor)
(defoverload executor-workflows            terra-executor-type terra-executor-workflows)
(defoverload executor-throw-if-invalid-retry-filters
  terra-executor-type terra-executor-throw-if-invalid-retry-filters)
(defoverload executor-retry-workflows!     terra-executor-type terra-executor-retry-workflows)

(defoverload stage/peek-queue   terra-executor-type peek-terra-executor-queue)
(defoverload stage/pop-queue!   terra-executor-type pop-terra-executor-queue)
(defoverload stage/queue-length terra-executor-type terra-executor-queue-length)
(defoverload stage/done?        terra-executor-type terra-executor-done?)

(defoverload util/to-edn terra-executor-type terra-executor-to-edn)

;; Generic executor-level specs following all implementations
(s/def ::executor ::terra-executor)
(s/def ::executor-workflow ::terra-executor-workflow)

(ns wfl.executor
  (:require [clojure.edn              :as edn]
            [clojure.set              :as set]
            [wfl.api.workloads        :refer [defoverload]]
            [wfl.jdbc                 :as jdbc]
            [wfl.service.firecloud    :as firecloud]
            [wfl.service.postgres     :as postgres]
            [wfl.service.rawls        :as rawls]
            [wfl.stage                :as stage]
            [wfl.util                 :as util :refer [utc-now]])
  (:import [clojure.lang ExceptionInfo]
           [wfl.util UserException])
  (:use [clojure.pprint :as pprint]))

;; executor operations
(defmulti update-executor!
  "Consume items from the `upstream-queue` and enqueue to the `executor` queue
   for consumption by a later processing stage, performing any external effects
   as necessary. Implementations should avoid maintaining in-memory state and
   making long-running external calls, favouring internal queues to manage such
   tasks asynchronously between invocations.  Note that the `Executor` and
   `Queue` are parameterised types and the `Queue`'s parameterisation must be
   convertible to the `Executor`s."
  (fn [_upstream-queue executor] (:type executor)))

(defmulti executor-workflows
  "Return the workflows managed by the `executor"
  (fn [_transaction executor] (:type executor)))

(defmulti executor-workflows-by-status
  "Use db `transaction` to return the workflows created by the `executor`
   matching `status`"
  (fn [_transaction executor _status] (:type executor)))

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

;; Terra Executor
(def ^:private terra-executor-name  "Terra")
(def ^:private terra-executor-type  "TerraExecutor")
(def ^:private terra-executor-table "TerraExecutor")
(def ^:private terra-executor-serialized-fields
  {:workspace                  :workspace
   :methodConfiguration        :method_configuration
   :methodConfigurationVersion :method_configuration_version
   :fromSource                 :from_source})

(defn ^:private create-terra-executor [tx id request]
  (let [create  "CREATE TABLE %s OF TerraExecutorDetails (PRIMARY KEY (id))"
        alter   "ALTER TABLE %s ALTER COLUMN id ADD GENERATED ALWAYS AS IDENTITY"
        details (format "%s_%09d" terra-executor-type id)]
    (jdbc/db-do-commands tx [(format create details) (format alter details)])
    [terra-executor-type
     (-> (select-keys request (keys terra-executor-serialized-fields))
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

(defn ^:private method-config-or-throw [workspace methodconfig]
  (try
    (firecloud/get-method-configuration workspace methodconfig)
    (catch ExceptionInfo cause
      (throw (UserException.
              "Cannot access method configuration in workspace"
              {:workspace           workspace
               :methodConfiguration methodconfig
               :status              (-> cause ex-data :status)}
              cause)))))

(defn ^:private throw-on-method-config-version-mismatch
  [{:keys [methodConfigVersion] :as methodconfig} expected]
  (when-not (== expected methodConfigVersion)
    (throw (UserException.
            "Unexpected method configuration version"
            {:methodConfiguration methodconfig
             :expected            expected
             :actual              methodConfigVersion}))))

(defn verify-terra-executor
  "Verify the method-configuration exists."
  [{:keys [skipValidation
           workspace
           methodConfiguration
           methodConfigurationVersion
           fromSource] :as executor}]
  (when-not skipValidation
    (firecloud/workspace-or-throw workspace)
    (when-not (= "importSnapshot" fromSource)
      (throw
       (UserException. "Unsupported coercion" (util/make-map fromSource))))
    (throw-on-method-config-version-mismatch
     (method-config-or-throw workspace methodConfiguration)
     methodConfigurationVersion))
  executor)

(defn ^:private from-source
  "Coerce `source-item` to form understood by `executor` via `fromSource`."
  [{:keys [workspace fromSource] :as executor}
   {:keys [name id]              :as _source-item}]
  (cond (= "importSnapshot" fromSource)
        (rawls/create-or-get-snapshot-reference workspace id name)
        :else
        (throw (ex-info "Unknown fromSource" {:executor executor}))))

(defn ^:private update-method-configuration!
  "Update `methodConfiguration` in `workspace` with snapshot reference `name`
  as :dataReferenceName via Firecloud.
  Update executor table record ID with incremented `methodConfigurationVersion`."
  [{:keys [id
           workspace
           methodConfiguration
           methodConfigurationVersion] :as _executor}
   {:keys [name]                       :as _reference}]
  (let [current (method-config-or-throw workspace methodConfiguration)
        _       (throw-on-method-config-version-mismatch
                 current methodConfigurationVersion)
        inc'd   (inc methodConfigurationVersion)]
    (firecloud/update-method-configuration
     workspace methodConfiguration
     (assoc current :dataReferenceName name :methodConfigVersion inc'd))
    (jdbc/with-db-transaction [tx (postgres/wfl-db-config)]
      (jdbc/update! tx terra-executor-table
                    {:method_configuration_version inc'd}
                    ["id = ?" id]))))

(defn ^:private create-submission!
  "Update `methodConfiguration` to use `reference`.
  Create and return submission in `workspace` for `methodConfiguration` via Firecloud."
  [{:keys [workspace methodConfiguration] :as executor} reference]
  (update-method-configuration! executor reference)
  (firecloud/submit-method workspace methodConfiguration))

(defn ^:private allocate-submission
  "Write or allocate workflow records for `submission` in `details` table."
  [{:keys [details]                :as _executor}
   {:keys [referenceId]            :as _reference}
   {:keys [submissionId workflows] :as _submission}]
  (letfn [(to-row [now {:keys [status workflowId entityName] :as _workflow}]
            {:reference  referenceId
             :submission submissionId
             :workflow   workflowId
             :entity     entityName
             :status     status
             :updated    now})]
    (jdbc/with-db-transaction [tx (postgres/wfl-db-config)]
      (->> workflows
           (map (partial to-row (utc-now)))
           (jdbc/insert-multi! tx details)))))

;; Workflows in newly created firecloud submissions may not have been scheduled
;; yet and thus do not have a workflow uuid. Thus, we first "allocate" the
;; workflow in the database and then later assign workflow uuids.
(defn ^:private update-unassigned-workflow-uuids!
  "Assign workflow uuids from previous submissions"
  [{:keys [workspace details] :as executor}]
  (letfn [(read-a-submission-without-workflows []
            (let [query "SELECT id, submission, entity FROM %s
                         WHERE submission IN (
                             SELECT submission FROM %s WHERE workflow IS NULL
                             LIMIT 1
                         )"]
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
        (->> workflows
             (map #(update % :workflowEntity :entityName))
             (sort-by :workflowEntity)
             (map zip-record (sort-by :entity records))
             (write-workflow-statuses (utc-now)))))))

(defn ^:private update-terra-workflow-statuses!
  "Update statuses in DETAILS table for active or failed WORKSPACE workflows.
  Return EXECUTOR."
  [{:keys [workspace details] :as _executor}]
  (letfn [(read-active-or-failed-workflows []
            (let [query "SELECT * FROM %s
                         WHERE submission IS NOT NULL
                         AND   workflow   IS NOT NULL
                         AND   status     NOT IN ('Succeeded', 'Aborted')
                         ORDER BY id ASC"]
              (jdbc/with-db-transaction [tx (postgres/wfl-db-config)]
                (jdbc/query tx (format query details)))))
          (update-status-from-firecloud
            [{:keys [submission workflow] :as record}]
            (->> (firecloud/get-workflow workspace submission workflow)
                 :status
                 (assoc record :status)))
          (write-workflow-statuses [now records]
            (jdbc/with-db-transaction [tx (postgres/wfl-db-config)]
              (run!
               (fn [{:keys [id status] :as _record}]
                 (jdbc/update! tx details {:status status :updated now}
                               ["id = ?" id]))
               (sort-by :entity records))))]
    (->> (read-active-or-failed-workflows)
         (mapv update-status-from-firecloud)
         (write-workflow-statuses (utc-now)))))

(defn ^:private update-terra-executor
  "Create new submission from new `source` snapshot if available,
  writing its workflows to `details` table.
  Update statuses for active or failed workflows in `details` table.
  Return `executor`."
  [source executor]
  (when-let [snapshot (stage/peek-queue source)]
    (let [reference  (from-source executor snapshot)
          submission (create-submission! executor reference)]
      (allocate-submission executor reference submission)
      (stage/pop-queue! source)))
  (update-unassigned-workflow-uuids! executor)
  (update-terra-workflow-statuses! executor)
  executor)

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
  (let [query "SELECT * FROM %s
               WHERE consumed IS NULL
               AND   status = 'Succeeded'
               ORDER BY id ASC
               LIMIT 1"]
    (jdbc/with-db-transaction [tx (postgres/wfl-db-config)]
      (->> (format query details)
           (jdbc/query tx)
           first))))

(defn ^:private peek-terra-executor-queue
  "Get first unconsumed successful workflow from `executor` queue."
  [{:keys [workspace] :as executor}]
  (when-let [{:keys [submission workflow] :as record}
             (peek-terra-executor-details executor)]
    (combine-record-workflow-and-outputs
     record
     (firecloud/get-workflow workspace submission workflow)
     (firecloud/get-workflow-outputs workspace submission workflow))))

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
  "Return the number workflows in the `_executor` queue that are yet to be
   consumed."
  [{:keys [details] :as _executor}]
  (let [query "SELECT COUNT(*) FROM %s
               WHERE consumed IS NULL
               AND   status   NOT IN ('Failed', 'Aborted')"]
    (jdbc/with-db-transaction [tx (postgres/wfl-db-config)]
      (->> (format query details)
           (jdbc/query tx)
           first
           :count))))

(defn ^:private terra-workflows-from-records
  [{:keys [workspace] :as _executor} records]
  (letfn [(from-record [{:keys [workflow submission status] :as record}]
            (combine-record-workflow-and-outputs
             record
             (firecloud/get-workflow workspace submission workflow)
             (when (= "Succeeded" status)
               (firecloud/get-workflow-outputs workspace submission workflow))))]
    (map from-record records)))

;; terra-executor-workflows and terra-executor-workflows-by-status do not return
;; workflows that are being or have been retied. Why?
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

(defn ^:private terra-executor-workflows
  "Return all the non-retried workflows executed by the `executor`."
  [tx {:keys [details] :as executor}]
  (when-not (postgres/table-exists? tx details)
    (throw (ex-info "Missing executor details table" {:table details})))
  (let [query "SELECT * FROM %s
               WHERE workflow IS NOT NULL
               AND   retry    IS NULL
               ORDER BY id ASC"]
    (terra-workflows-from-records
     executor
     (jdbc/query tx (format query details)))))

(defn ^:private terra-executor-workflows-by-status
  "Return all the non-retried workflows matching `status` executed by the
  `executor`."
  [tx {:keys [details] :as executor} status]
  (when-not (postgres/table-exists? tx details)
    (throw (ex-info "Missing executor details table" {:table details})))
  (let [query "SELECT * FROM %s
               WHERE workflow IS NOT NULL
               AND retry      IS NULL
               AND status     = ?
               ORDER BY id ASC"]
    (terra-workflows-from-records
     executor
     (jdbc/query tx [(format query details) status]))))

(defn ^:private terra-executor-done? [executor]
  (zero? (stage/queue-length executor)))

(defn ^:private terra-executor-to-edn [executor]
  (-> executor
      (util/select-non-nil-keys (keys terra-executor-serialized-fields))
      (assoc :name terra-executor-name)))

(defn retry-workflows
      [workload workflows]

      ; Get the rows from the terra details table for the workflows
      ; iterate through the results and get the distinct reference values
      ; resubmit the reference values
      ; write back to the table, putting the new workflow id into the retry field of the failed one
      (let [workflow_ids (util/to-quoted-comma-separated-list (map :uuid workflows))
            {:keys [workspace details] :as executor} (:executor workload)
            get_query (format "SELECT * FROM %s WHERE workflow IN (%s)" details workflow_ids)
            results (jdbc/query (postgres/wfl-db-config) [get_query])
            distinct_references (->> (map :reference results)
                                     set
                                     (map (partial rawls/get-snapshot-reference workspace)))

            ]
           (jdbc/with-db-transaction [tx (postgres/wfl-db-config)]
                                     (for [ref distinct_references]
                                       (let [submission (create-submission! executor ref)
                                             result (allocate-submission executor ref submission)
                                             new_workflow_id (:id result)
                                             entity_id (:entity result)
                                             update_query (format "UPDATE %s
                                                                   SET retry=%s
                                                                   WHERE entity = '%s'
                                                                   AND workflow IS NOT NULL;"
                                                                   details
                                                                   new_workflow_id
                                                                   entity_id)
                                             ]
                                            (pprint result)
                                            ;(update-unassigned-workflow-uuids! executor)
                                            ;(update-terra-workflow-statuses! executor)
                                            ;(jdbc/execute! tx update_query)
                                            )
                                          )
                                     )
           )
      )


(defoverload create-executor! terra-executor-name create-terra-executor)
(defoverload load-executor!   terra-executor-type load-terra-executor)

(defoverload update-executor!             terra-executor-type update-terra-executor)
(defoverload executor-workflows           terra-executor-type terra-executor-workflows)
(defoverload executor-workflows-by-status terra-executor-type terra-executor-workflows-by-status)

(defoverload stage/validate-or-throw terra-executor-name verify-terra-executor)
(defoverload stage/done?             terra-executor-type terra-executor-done?)

(defoverload stage/peek-queue   terra-executor-type peek-terra-executor-queue)
(defoverload stage/pop-queue!   terra-executor-type pop-terra-executor-queue)
(defoverload stage/queue-length terra-executor-type terra-executor-queue-length)

(defoverload util/to-edn terra-executor-type terra-executor-to-edn)

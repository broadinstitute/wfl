(ns wfl.module.covid
  "Manage the Sarscov2IlluminaFull pipeline."
  (:require [clojure.data :as data]
            [clojure.edn :as edn]
            [clojure.set :as set]
            [clojure.string :as str]
            [clojure.tools.logging :as log]
            [wfl.api.workloads :as workloads :refer [defoverload]]
            [wfl.jdbc :as jdbc]
            [wfl.service.firecloud :as firecloud]
            [wfl.service.postgres :as postgres]
            [wfl.service.rawls :as rawls]
            [wfl.source :as source]
            [wfl.stage :as stage]
            [wfl.util :as util :refer [utc-now]]
            [wfl.wfl :as wfl])
  (:import [clojure.lang ExceptionInfo]
           [java.time OffsetDateTime ZoneId]
           [java.util UUID]
           [wfl.util UserException]))

(def pipeline nil)

;; executor operations
(defmulti create-executor!
  "Use `tx` and workload `id` to write the executor to persisted storage and
   return a [type item] pair to be written into the parent table."
  (fn [_tx _id executor-request] (:name executor-request)))

(defmulti update-executor!
  "Update the executor with the `source`"
  (fn [_source executor] (:type executor)))

(defmulti executor-workflows
  "Use `tx` to return the workflows created by the `executor"
  (fn [_tx executor] (:type executor)))

(defmulti load-executor!
  "Use `tx` to load the `workload` executor with `executor_type`."
  (fn [_tx workload] (:executor_type workload)))

;; sink operations
(defmulti create-sink!
  "Use `tx` and workload `id` to write the `sink-request` to persisted
  storage and return a [type item] pair for writing to the parent table."
  (fn [_tx _id sink-request] (:name sink-request)))

(defmulti update-sink!
  "Update the `sink` with the `executor`."
  (fn [_executor sink] (:type sink)))

(defmulti load-sink!
  "Use `tx` to load the `workload` sink with `sink_type`."
  (fn [_tx workload] (:sink_type workload)))

;; Workload
(defn ^:private patch-workload [tx {:keys [id]} colls]
  (jdbc/update! tx :workload colls ["id = ?" id]))

(def ^:private workload-metadata-keys
  [:commit
   :created
   :creator
   :executor
   :finished
   :labels
   :sink
   :source
   :started
   :stopped
   :updated
   :uuid
   :version
   :watchers])

(defn ^:private add-workload-metadata
  "Use `tx` to record the workload metadata in `request` in the workload table
   and return the ID the of the new row."
  [tx {:keys [project] :as request}]
  (letfn [(combine-labels [labels]
            (->> (str "project:" project)
                 (conj labels)
                 set
                 sort
                 vec))]
    (-> (update request :labels combine-labels)
        (select-keys [:creator :watchers :labels :project])
        (merge (select-keys (wfl/get-the-version) [:commit :version]))
        (assoc :executor ""
               :output   ""
               :release  ""
               :wdl      ""
               :uuid     (UUID/randomUUID))
        (->> (jdbc/insert! tx :workload) first :id))))

(def ^:private update-workload-query
  "UPDATE workload
   SET    source_type    = ?::source
   ,      source_items   = ?
   ,      executor_type  = ?::executor
   ,      executor_items = ?
   ,      sink_type      = ?::sink
   ,      sink_items     = ?
   WHERE  id = ?")

(defn ^:private create-covid-workload
  [tx {:keys [source executor sink] :as request}]
  (let [[source executor sink] (mapv stage/validate-or-throw [source executor sink])
        id                     (add-workload-metadata tx request)]
    (jdbc/execute!
     tx
     (concat [update-workload-query]
             (source/create-source! tx id source)
             (create-executor! tx id executor)
             (create-sink! tx id sink)
             [id]))
    (workloads/load-workload-for-id tx id)))

(defn ^:private load-covid-workload-impl [tx {:keys [id] :as workload}]
  (let [src-exc-sink {:source   (source/load-source! tx workload)
                      :executor (load-executor! tx workload)
                      :sink     (load-sink! tx workload)}]
    (as-> workload $
      (select-keys $ workload-metadata-keys)
      (merge $ src-exc-sink)
      (filter second $)
      (into {:type :workload :id id} $))))

(defn ^:private start-covid-workload
  "Start creating and managing workflows from the source."
  [tx {:keys [started] :as workload}]
  (letfn [(start [{:keys [id source] :as workload} now]
            (source/start-source! tx source)
            (patch-workload tx workload {:started now :updated now})
            (workloads/load-workload-for-id tx id))]
    (if-not started (start workload (utc-now)) workload)))

(defn ^:private update-covid-workload
  "Use transaction `tx` to update `workload` statuses."
  [tx {:keys [started finished] :as workload}]
  (letfn [(update! [{:keys [id source executor sink] :as workload} now]
            (-> (source/update-source! source)
                (update-executor! executor)
                (update-sink! sink))
            (patch-workload tx workload {:updated now})
            (when (every? stage/done? [source executor sink])
              (patch-workload tx workload {:finished now}))
            (workloads/load-workload-for-id tx id))]
    (if (and started (not finished)) (update! workload (utc-now)) workload)))

(defn ^:private stop-covid-workload
  "Use transaction `tx` to stop the `workload` looking for new data."
  [tx {:keys [started stopped finished] :as workload}]
  (letfn [(stop! [{:keys [id source] :as workload} now]
            (source/stop-source! tx source)
            (patch-workload tx workload {:stopped now :updated now})
            (when-not (:started workload)
              (patch-workload tx workload {:finished now}))
            (workloads/load-workload-for-id tx id))]
    (when-not started
      (throw (UserException. "Cannot stop workload before it's been started."
                             {:workload workload})))
    (if-not (or stopped finished) (stop! workload (utc-now)) workload)))

(defn ^:private workload-to-edn [workload]
  (-> workload
      (util/select-non-nil-keys workload-metadata-keys)
      (dissoc :pipeline)
      (update :source   util/to-edn)
      (update :executor util/to-edn)
      (update :sink     util/to-edn)))

(defoverload workloads/create-workload!   pipeline create-covid-workload)
(defoverload workloads/start-workload!    pipeline start-covid-workload)
(defoverload workloads/update-workload!   pipeline update-covid-workload)
(defoverload workloads/stop-workload!     pipeline stop-covid-workload)
(defoverload workloads/load-workload-impl pipeline load-covid-workload-impl)
(defmethod   workloads/workflows          pipeline
  [tx {:keys [executor] :as _workload}]
  (executor-workflows tx executor))
(defoverload workloads/to-edn             pipeline workload-to-edn)

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

(defn ^:private workspace-or-throw [workspace]
  (try
    (firecloud/workspace workspace)
    (catch ExceptionInfo cause
      (throw (UserException.
              "Cannot access workspace"
              {:workspace           workspace
               :status              (-> cause ex-data :status)}
              cause)))))

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
    (workspace-or-throw workspace)
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

(defn ^:private terra-executor-workflows
  [tx {:keys [workspace details] :as _executor}]
  (when-not (postgres/table-exists? tx details)
    (throw (ex-info "Missing executor details table" {:table details})))
  (letfn [(from-record [{:keys [workflow submission status] :as record}]
            (combine-record-workflow-and-outputs
             record
             (firecloud/get-workflow workspace submission workflow)
             (when (= "Succeeded" status)
               (firecloud/get-workflow-outputs workspace submission workflow))))]
    (let [query "SELECT * FROM %s WHERE workflow IS NOT NULL ORDER BY id ASC"]
      (map from-record (jdbc/query tx (format query details))))))

(defn ^:private terra-executor-done? [executor]
  (zero? (stage/queue-length executor)))

(defn ^:private terra-executor-to-edn [executor]
  (-> executor
      (util/select-non-nil-keys (keys terra-executor-serialized-fields))
      (assoc :name terra-executor-name)))

(defoverload stage/validate-or-throw terra-executor-name verify-terra-executor)

(defoverload create-executor!   terra-executor-name create-terra-executor)
(defoverload update-executor!   terra-executor-type update-terra-executor)
(defoverload load-executor!     terra-executor-type load-terra-executor)
(defoverload executor-workflows terra-executor-type terra-executor-workflows)

(defoverload stage/peek-queue   terra-executor-type peek-terra-executor-queue)
(defoverload stage/pop-queue!   terra-executor-type pop-terra-executor-queue)
(defoverload stage/queue-length terra-executor-type terra-executor-queue-length)
(defoverload stage/done?        terra-executor-type terra-executor-done?)

(defoverload util/to-edn terra-executor-type terra-executor-to-edn)

;; Terra Workspace Sink
(def ^:private terra-workspace-sink-name  "Terra Workspace")
(def ^:private terra-workspace-sink-type  "TerraWorkspaceSink")
(def ^:private terra-workspace-sink-table "TerraWorkspaceSink")
(def ^:private terra-workspace-sink-serialized-fields
  {:workspace   :workspace
   :entityType  :entity_type
   :fromOutputs :from_outputs
   :identifier  :identifier})

(defn ^:private create-terra-workspace-sink [tx id request]
  (let [create  "CREATE TABLE %s OF TerraWorkspaceSinkDetails (PRIMARY KEY (id))"
        alter   "ALTER TABLE %s ALTER COLUMN id ADD GENERATED ALWAYS AS IDENTITY"
        details (format "%s_%09d" terra-workspace-sink-type id)]
    (jdbc/db-do-commands tx [(format create details) (format alter details)])
    [terra-workspace-sink-type
     (-> (select-keys request (keys terra-workspace-sink-serialized-fields))
         (update :fromOutputs pr-str)
         (set/rename-keys terra-workspace-sink-serialized-fields)
         (assoc :details details)
         (->> (jdbc/insert! tx terra-workspace-sink-table) first :id str))]))

(defn ^:private load-terra-workspace-sink [tx {:keys [sink_items] :as workload}]
  (if-let [id (util/parse-int sink_items)]
    (-> (postgres/load-record-by-id! tx terra-workspace-sink-table id)
        (set/rename-keys (set/map-invert terra-workspace-sink-serialized-fields))
        (update :fromOutputs edn/read-string)
        (assoc :type terra-workspace-sink-type))
    (throw (ex-info "Invalid sink_items" {:workload workload}))))

(def unknown-entity-type-error-message
  "The entityType was not found in workspace.")

(def malformed-from-outputs-error-message
  (str/join " " ["fromOutputs must define a mapping from workflow outputs"
                 "to the attributes of entityType."]))

(def unknown-attributes-error-message
  (str/join " " ["Found additional attributes in fromOutputs that are not"
                 "present in the entityType."]))

(defn ^:private verify-terra-sink!
  "Verify that the WFL has access the `workspace`."
  [{:keys [entityType fromOutputs skipValidation workspace] :as sink}]
  (when-not skipValidation
    (workspace-or-throw workspace)
    (let [entity-type  (keyword entityType)
          entity-types (firecloud/list-entity-types workspace)
          types        (-> entity-types keys set)]
      (when-not (types entity-type)
        (throw (UserException. unknown-entity-type-error-message
                               (util/make-map entityType types workspace))))
      (when-not (map? fromOutputs)
        (throw (UserException. malformed-from-outputs-error-message
                               (util/make-map entityType fromOutputs))))
      (let [attributes    (->> (get-in entity-types [entity-type :attributeNames])
                               (cons (str entityType "_id"))
                               (mapv keyword))
            [missing _ _] (data/diff (set (keys fromOutputs)) (set attributes))]
        (when (seq missing)
          (throw (UserException. unknown-attributes-error-message
                                 {:entityType  entityType
                                  :attributes  (sort attributes)
                                  :missing     (sort missing)
                                  :fromOutputs fromOutputs}))))))
  sink)

;; visible for testing
(defn rename-gather
  "Transform the `values` using the transformation defined in `mapping`."
  [values mapping]
  (letfn [(literal? [x] (str/starts-with? x "$"))
          (go! [v]
            (cond (literal? v) (subs v 1 (count v))
                  (string?  v) (values (keyword v))
                  (map?     v) (rename-gather values v)
                  (coll?    v) (keep go! v)
                  :else        (throw (ex-info "Unknown operation"
                                               {:operation v}))))]
    (into {} (for [[k v] mapping] [k (go! v)]))))

(defn ^:private terra-workspace-sink-to-attributes
  [{:keys [outputs] :as workflow} fromOutputs]
  (when-not (map? fromOutputs)
    (throw (IllegalStateException. "fromOutputs is malformed")))
  (try
    (rename-gather outputs fromOutputs)
    (catch Exception cause
      (throw (ex-info "Failed to coerce workflow outputs to attribute values"
                      {:fromOutputs fromOutputs :workflow workflow}
                      cause)))))

(defn ^:private entity-exists?
  "True when the `entity` exists in the `workspace`."
  [workspace entity]
  (try
    (firecloud/get-entity workspace entity)
    (catch ExceptionInfo ex
      (when (not= (-> ex ex-data :status) 404)
        (throw ex)))))

(defn ^:private update-terra-workspace-sink
  [executor {:keys [fromOutputs workspace entityType identifier details] :as _sink}]
  (when-let [{:keys [uuid outputs] :as workflow} (stage/peek-queue executor)]
    (log/debug "coercing workflow" uuid "outputs to" entityType)
    (let [attributes (terra-workspace-sink-to-attributes workflow fromOutputs)
          [_ name :as entity] [entityType (outputs (keyword identifier))]]
      (when (entity-exists? workspace entity)
        (log/debug "entity" name "already exists - deleting previous entity.")
        (firecloud/delete-entities workspace [entity]))
      (log/debug "upserting workflow" uuid "outputs as" name)
      (rawls/batch-upsert workspace [(conj entity attributes)])
      (stage/pop-queue! executor)
      (log/info "sunk workflow" uuid "to" workspace "as" name)
      (jdbc/with-db-transaction [tx (postgres/wfl-db-config)]
        (jdbc/insert! tx details {:entity   name
                                  :updated  (utc-now)
                                  :workflow uuid})))))

(defn ^:private terra-workspace-sink-done? [_sink] true)

(defn ^:private terra-workspace-sink-to-edn [sink]
  (-> sink
      (util/select-non-nil-keys (keys terra-workspace-sink-serialized-fields))
      (assoc :name terra-workspace-sink-name)))

(defoverload stage/validate-or-throw terra-workspace-sink-name verify-terra-sink!)

(defoverload create-sink!  terra-workspace-sink-name create-terra-workspace-sink)
(defoverload load-sink!    terra-workspace-sink-type load-terra-workspace-sink)
(defoverload update-sink!  terra-workspace-sink-type update-terra-workspace-sink)

(defoverload stage/done? terra-workspace-sink-type terra-workspace-sink-done?)

(defoverload util/to-edn terra-workspace-sink-type terra-workspace-sink-to-edn)

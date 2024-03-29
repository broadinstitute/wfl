(ns wfl.sink
  "Workload sink interface and its implementations."
  (:require [clojure.data               :as data]
            [clojure.data.json          :as json]
            [clojure.edn                :as edn]
            [clojure.set                :as set]
            [clojure.spec.alpha         :as s]
            [clojure.string             :as str]
            [wfl.api.workloads          :as workloads :refer [defoverload]]
            [wfl.environment            :as env]
            [wfl.jdbc                   :as jdbc]
            [wfl.log                    :as log]
            [wfl.mime-type              :as mime-type]
            [wfl.module.all             :as all]
            [wfl.service.datarepo       :as datarepo]
            [wfl.service.firecloud      :as firecloud]
            [wfl.service.google.storage :as storage]
            [wfl.service.postgres       :as postgres]
            [wfl.service.rawls          :as rawls]
            [wfl.stage                  :as stage]
            [wfl.util                   :as util :refer [utc-now]])
  (:import [wfl.util UserException]))

(defmulti create-sink!
  "Create a `Sink` instance using the database `transaction` and configuration
   in the sink `request` and return a `[type items]` pair to be written to a
   workload record as `sink_type` and `sink_items`.
   Notes:
   - This is a factory method registered for workload creation.
   - The `Sink` type string must match a value of the `sink` enum in the
     database schema.
   - This multimethod is type-dispatched on the `:name` association in the
     `request`."
  (fn [_transaction _workload-id request] (:name request)))

;; Interface
(defmulti load-sink!
  "Return the `Sink` implementation associated with the `sink_type` and
   `sink_items` fields of the `workload` row in the database.
   Note that this multimethod is type-dispatched on the `:sink_type`
   association in the `workload`."
  (fn [_transaction workload] (:sink_type workload)))

(defmulti update-sink!
  "Update the internal state of the `Workload`'s sink, consuming objects from the
   `Workload`'s executor queue, performing any external effects as required.
   Implementations should avoid maintaining in-memory state and making long-
   running external calls, favouring internal queues to manage such tasks
   asynchronously between invocations. Note that the `Workload`'s Sink and its Executor Queue are
   parameterised types and the Executor Queue's parameterisation must be convertible
   to the Sink's."
  (fn [{:keys [sink] :as _workload}] (:type sink)))

(defmethod create-sink! :default
  [_ _ {:keys [name] :as request}]
  (throw (UserException.
          "Invalid sink name"
          {:name    name
           :request request
           :status  400
           :sources (-> create-sink! methods (dissoc :default) keys)})))

;; Terra Workspace Sink
(def ^:private ^:const terra-workspace-sink-name  "Terra Workspace")
(def ^:private ^:const terra-workspace-sink-type  "TerraWorkspaceSink")
(def ^:private ^:const terra-workspace-sink-table "TerraWorkspaceSink")
(def ^:private ^:const terra-workspace-sink-serialized-fields
  {:workspace   :workspace
   :entityType  :entity_type
   :fromOutputs :from_outputs
   :identifier  :identifier})

(s/def ::identifier string?)
(s/def ::fromOutputs map?)

;; reitit coercion spec
(s/def ::terra-workspace-sink
  (s/and #(= terra-workspace-sink-name (:name %))
         (s/keys :req-un [::all/workspace
                          ::all/entityType
                          ::identifier
                          ::fromOutputs])))

(defn ^:private write-terra-workspace-sink [tx id request]
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

(def ^:private unknown-entity-type-error-message
  "The entityType was not found in workspace.")

(def ^:private terra-workspace-malformed-from-outputs-message
  (str/join " " ["fromOutputs must define a mapping from workflow outputs"
                 "to the attributes of entityType."]))

(def ^:private unknown-attributes-error-message
  (str/join " " ["Found additional attributes in fromOutputs that are not"
                 "present in the entityType."]))

(defn terra-workspace-sink-validate-request-or-throw
  "Verify that the WFL has access the `workspace`."
  [{:keys [entityType fromOutputs skipValidation workspace] :as sink}]
  (when-not skipValidation
    (firecloud/workspace-or-throw workspace)
    (let [entity-type  (keyword entityType)
          entity-types (firecloud/list-entity-types workspace)
          types        (-> entity-types keys set)]
      (when-not (types entity-type)
        (throw (UserException. unknown-entity-type-error-message
                               (util/make-map entityType types workspace))))
      (when-not (map? fromOutputs)
        (throw (UserException. terra-workspace-malformed-from-outputs-message
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

(defn ^:private tdr-outputs-bucket
  "Build the google storage file path for the TDR outputs bucket."
  [path]
  (storage/gs-url (env/getenv "WFL_TDR_TEMPORARY_STORAGE_BUCKET") path))

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

(def ^:private entity-name-not-found-error-message
  (str/join \space ["Entity name not found:"
                    "sink.identifer not in workflow outputs or inputs"]))

(defn ^:private throw-or-entity-name-from-workflow
  "Return entity name from `identifier`'s match in `workflow`:
   first checking `outputs` and falling back to `inputs`."
  [{:keys [inputs outputs] :as workflow} {:keys [identifier] :as sink}]
  (let [kw-id       (keyword identifier)
        entity-name (or (get outputs kw-id) (get inputs kw-id))]
    (when (nil? entity-name)
      (throw (ex-info entity-name-not-found-error-message
                      {:sink     sink
                       :workflow workflow})))
    entity-name))

(defn ^:private update-terra-workspace-sink
  "Write outputs from consumable `executor` workflows
   to `entityType` table in `workspace`."
  [{executor :executor
    {:keys [fromOutputs workspace entityType details] :as sink} :sink :as workload}]
  (when-let [[_ {:keys [uuid] :as workflow}] (stage/peek-queue executor)]
    (let [entityName (throw-or-entity-name-from-workflow workflow sink)
          entity     [entityType entityName]
          attributes (terra-workspace-sink-to-attributes workflow fromOutputs)]
      (rawls/batch-upsert workspace [(conj entity attributes)])
      (stage/pop-queue! executor)
      (log/info "Sunk workflow outputs to Terra workspace"
                :workload   (workloads/to-log workload)
                :workflow   uuid
                :workspace  workspace
                :entityType entityType)
      (jdbc/with-db-transaction [tx (postgres/wfl-db-config)]
        (jdbc/insert! tx details {:entity   entityName
                                  :updated  (util/utc-now)
                                  :workflow uuid})))))

(defn ^:private terra-workspace-sink-done? [_sink] true)

(defn ^:private terra-workspace-sink-to-edn [sink]
  (-> sink
      (util/select-non-nil-keys (keys terra-workspace-sink-serialized-fields))
      (assoc :name terra-workspace-sink-name)))

(defmethod create-sink! terra-workspace-sink-name
  [tx id request]
  (write-terra-workspace-sink
   tx id (terra-workspace-sink-validate-request-or-throw request)))

(defoverload load-sink!   terra-workspace-sink-type load-terra-workspace-sink)
(defoverload update-sink! terra-workspace-sink-type update-terra-workspace-sink)
(defoverload stage/done?  terra-workspace-sink-type terra-workspace-sink-done?)

(defoverload util/to-edn terra-workspace-sink-type terra-workspace-sink-to-edn)

;; TerraDataRepo Sink
(def ^:private ^:const datarepo-sink-name  "Terra DataRepo")
(def ^:private ^:const datarepo-sink-type  "TerraDataRepoSink")
(def ^:private ^:const datarepo-sink-table "TerraDataRepoSink")
(def ^:private ^:const datarepo-sink-serialized-fields
  {:dataset     :dataset
   :table       :dataset_table
   :fromOutputs :from_outputs})

;; reitit coercion spec
(s/def ::terra-datarepo-sink
  (s/and #(= datarepo-sink-name (:name %))
         (s/keys :req-un [::all/dataset ::all/table ::fromOutputs])))

(defn ^:private write-datarepo-sink [tx id request]
  (let [create  "CREATE TABLE %s OF TerraDataRepoSinkDetails (PRIMARY KEY (id))"
        alter   "ALTER TABLE %s ALTER COLUMN id ADD GENERATED ALWAYS AS IDENTITY"
        details (format "%s_%09d" datarepo-sink-type id)]
    (jdbc/db-do-commands tx [(format create details) (format alter details)])
    [datarepo-sink-type
     (-> (select-keys request (keys datarepo-sink-serialized-fields))
         (update :dataset pr-str)
         (update :fromOutputs pr-str)
         (set/rename-keys datarepo-sink-serialized-fields)
         (assoc :details details)
         (->> (jdbc/insert! tx datarepo-sink-table) first :id str))]))

(defn ^:private load-datarepo-sink [tx {:keys [sink_items] :as workload}]
  (if-let [id (util/parse-int sink_items)]
    (-> (postgres/load-record-by-id! tx datarepo-sink-table id)
        (set/rename-keys (set/map-invert datarepo-sink-serialized-fields))
        (update :dataset edn/read-string)
        (update :fromOutputs edn/read-string)
        (assoc :type datarepo-sink-type))
    (throw (ex-info "Invalid sink_items" {:workload workload}))))

(def datarepo-malformed-from-outputs-message
  (str/join " " ["fromOutputs must define a mapping from workflow outputs"
                 "to columns in the table in the dataset."]))

(def unknown-columns-error-message
  (str/join " " ["Found column names in fromOutputs that are not columns of"
                 "the table in the dataset."]))

(defn datarepo-sink-validate-request-or-throw
  "Throw unless the user's sink `request` yields a valid configuration for a
   TerraDataRepoSink by ensuring all resources specified in the request exist."
  [{:keys [dataset table fromOutputs] :as request}]
  (let [dataset' (datarepo/datasets dataset)
        ;; eagerly evaluate for effects
        table'   (datarepo/table-or-throw table dataset')]
    (when-not (map? fromOutputs)
      (throw (UserException. datarepo-malformed-from-outputs-message
                             (util/make-map dataset fromOutputs))))
    (let [columns       (map (comp keyword :name) (:columns table'))
          [missing _ _] (data/diff (set (keys fromOutputs)) (set columns))]
      (when (seq missing)
        (throw (UserException. unknown-columns-error-message
                               {:dataset     dataset
                                :table       table
                                :columns     (sort columns)
                                :missing     (sort missing)
                                :fromOutputs fromOutputs}))))
    (assoc request :dataset dataset')))

(defn ^:private push-job [{:keys [details] :as _sink} job]
  (jdbc/with-db-transaction [tx (postgres/wfl-db-config)]
    (jdbc/insert! tx details job)))

(defn ^:private peek-job-queue [{:keys [details] :as _sink}]
  (let [query "SELECT * FROM %s
               WHERE status = 'succeeded' AND consumed IS NULL
               ORDER BY id LIMIT 1"]
    (jdbc/with-db-transaction [tx (postgres/wfl-db-config)]
      (postgres/throw-unless-table-exists tx details)
      (first (jdbc/query tx (format query details))))))

(defn ^:private pop-job-queue!
  [{:keys [details] :as _sink} {:keys [id] :as _job}]
  {:pre [(some? id) (integer? id)]}
  (jdbc/with-db-transaction [tx (postgres/wfl-db-config)]
    (jdbc/update! tx details {:consumed (utc-now)} ["id = ?" id])))

(def ^:private active-job-query
  (format
   "SELECT id, job, workflow FROM %%s WHERE status IN %s ORDER BY id ASC"
   (util/to-quoted-comma-separated-list datarepo/active?)))

(defn ^:private update-datarepo-job-statuses
  "Fetch and record the status of all 'running' jobs from tdr created by the
   `sink`. Throws `UserException` when any new job status is `failed`."
  [{:keys [details] :as _sink}]
  (letfn [(read-active-jobs []
            (jdbc/with-db-transaction [tx (postgres/wfl-db-config)]
              (map (juxt :id :job :workflow)
                   (jdbc/query tx (format active-job-query details)))))
          (write-job-status [id status]
            (jdbc/with-db-transaction [tx (postgres/wfl-db-config)]
              (jdbc/update! tx details
                            {:status status :updated (utc-now)}
                            ["id = ?" id])))]
    (doseq [[id job-id workflow] (read-active-jobs)]
      (let [job    (datarepo/job-metadata job-id)
            status (-> job :job_status str/lower-case)]
        (write-job-status id status)
        (when (= "failed" status)
          (throw (UserException. "A DataRepo ingest job failed"
                                 {:job      job
                                  :workflow workflow})))))))

(defn ^:private rename-gather-bulk
  "Transform `values` according to `mapping` for `workflow-id`and `table` in `dataset`.
   And HACK test outputs with `test-prefix` when specified."
  ([workflow-id dataset table values mapping]
   (rename-gather-bulk workflow-id dataset table values mapping nil))
  ([workflow-id {:keys [schema] :as dataset} table values mapping test-prefix]
   (letfn [(literal? [x] (str/starts-with? x "$"))
           (datatype [k] (let [columns (->> schema :tables
                                            (filter #(= table (:name %)))
                                            first :columns)]
                           (-> (filter #(= k (:name %)) columns)
                               first :datatype)))
           (fileref? [k] (= (datatype (name k)) "fileref"))
           (boolean? [k] (= (datatype (name k)) "boolean"))
           (get-target [url]
             (let [[_ obj] (storage/parse-gs-url url)]
               (str/join "/" [(or test-prefix "") workflow-id (util/basename obj)])))
           (go! [k v]
             (cond (fileref? k) (if-let [val (values (keyword v))]
                                  [k {:description (util/basename val)
                                      :mimeType    (mime-type/ext-mime-type val)
                                      :sourcePath  val
                                      :targetPath  (get-target val)}]
                                  [k nil])
                   (literal? v) [k (subs v 1 (count v))]
                   (boolean? v) [k (values (keyword v))]
                   (string?  v) [k (values (keyword v))]
                   (map?     v) [k (rename-gather-bulk workflow-id dataset table values v)]
                   (coll?    v) [k (keep (go! k v))]
                   (nil?     v) [k nil]
                   :else        (throw (ex-info "Unknown operation"
                                                {:operation v}))))]
     (into {} (for [[k v] mapping] (go! k v))))))

(defn ^:private to-dataset-row
  "Use `fromOutputs` and the schema provided for `table` within the
  `dataset` to coerce the `workflow` outputs into a row in the dataset
  table. The dataset table schema describes column types while
  `fromOutputs` provides a mapping between workflow outputs and the
  table."
  [dataset table fromOutputs {:keys [outputs uuid] :as workflow}]
  (when-not (map? fromOutputs)
    (throw (IllegalStateException. "fromOutputs is malformed")))
  (try
    (rename-gather-bulk uuid dataset table outputs fromOutputs)
    (catch Exception cause
      (throw (ex-info "Failed to coerce workflow outputs to dataset columns"
                      {:dataset     dataset
                       :fromOutputs fromOutputs
                       :outputs     outputs
                       :table       table
                       :workflow    workflow} cause)))))

(defn ^:private start-ingesting-outputs
  "Start ingesting the `workflow` outputs as a new row in the target
  dataset and push the TDR ingest job into the `sink`'s job queue."
  [{:keys [dataset table fromOutputs] :as sink}
   {:keys [uuid] :as workflow}]
  (let [file (tdr-outputs-bucket (str uuid ".json"))]
    (-> (to-dataset-row dataset table fromOutputs workflow)
        (json/write-str :escape-slash false)
        (storage/upload-content file))
    (->> (datarepo/ingest-table (:id dataset) file table)
         (assoc {:status "running" :workflow uuid} :job)
         (push-job sink))))

(defn ^:private update-datarepo-sink
  "Pull a workflow off the `_workload`'s `executor` queue and write its
  outputs as new rows in a TDR dataset table."
  [{:keys [executor sink] :as workload}]
  (when-let [[_ workflow] (stage/peek-queue executor)]
    (start-ingesting-outputs sink workflow)
    (stage/pop-queue! executor))
  (update-datarepo-job-statuses sink)
  (when-let [{:keys [job workflow] :as record} (peek-job-queue sink)]
    (try
      (let [result (datarepo/job-result job)]
        (if (< (:bad_row_count result) 1)
          (log/info "Sunk all workflow outputs to TDR dataset"
                    :workload (workloads/to-log workload)
                    :job-id   job
                    :workflow workflow)
          (throw (UserException. "Row failed to sink to TDR dataset"
                                 {:job-id job :workflow workflow}))))
      (finally
        (pop-job-queue! sink record)))))

(defn ^:private datarepo-sink-done?
  "True when all TDR ingest jobs created by `_sink` have terminated."
  [{:keys [details] :as _sink}]
  (let [query "SELECT COUNT(*) FROM %s
               WHERE status <> 'failed' AND consumed IS NULL"]
    (jdbc/with-db-transaction [tx (postgres/wfl-db-config)]
      (postgres/throw-unless-table-exists tx details)
      (-> (jdbc/query tx (format query details)) first :count zero?))))

(defn ^:private datarepo-sink-to-edn [sink]
  (-> sink
      (util/select-non-nil-keys (keys datarepo-sink-serialized-fields))
      (update :dataset :id)
      (assoc :name datarepo-sink-name)))

(defmethod create-sink! datarepo-sink-name
  [tx id request]
  (write-datarepo-sink
   tx id (datarepo-sink-validate-request-or-throw request)))

(defoverload load-sink!   datarepo-sink-type load-datarepo-sink)
(defoverload update-sink! datarepo-sink-type update-datarepo-sink)
(defoverload stage/done?  datarepo-sink-type datarepo-sink-done?)

(defoverload util/to-edn datarepo-sink-type datarepo-sink-to-edn)

;; reitit http coercion specs for a sink
;; Recall s/or doesn't work (https://github.com/metosin/reitit/issues/494)
(s/def ::sink
  #(condp = (:name %)
     terra-workspace-sink-name (s/valid? ::terra-workspace-sink %)
     datarepo-sink-name        (s/valid? ::terra-datarepo-sink %)))

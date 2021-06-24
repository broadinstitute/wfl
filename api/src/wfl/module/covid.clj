(ns wfl.module.covid
  "Manage the Sarscov2IlluminaFull pipeline."
  (:require [clojure.data :as data]
            [clojure.edn :as edn]
            [clojure.set :as set]
            [clojure.string :as str]
            [clojure.tools.logging :as log]
            [wfl.api.workloads :as workloads :refer [defoverload]]
            [wfl.executor :as executor]
            [wfl.jdbc :as jdbc]
            [wfl.module.batch :as batch]
            [wfl.service.firecloud :as firecloud]
            [wfl.service.postgres :as postgres]
            [wfl.service.rawls :as rawls]
            [wfl.source :as source]
            [wfl.stage :as stage]
            [wfl.util :as util :refer [utc-now]]
            [wfl.wfl :as wfl])
  (:import [clojure.lang ExceptionInfo]
           [java.util UUID]
           [wfl.util UserException]))

(def pipeline nil)

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
             (executor/create-executor! tx id executor)
             (create-sink! tx id sink)
             [id]))
    (workloads/load-workload-for-id tx id)))

(defn ^:private load-covid-workload-impl [tx {:keys [id] :as workload}]
  (let [src-exc-sink {:source   (source/load-source! tx workload)
                      :executor (executor/load-executor! tx workload)
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
                (executor/update-executor! executor)
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

(defoverload workloads/create-workload!    pipeline create-covid-workload)
(defoverload workloads/start-workload!     pipeline start-covid-workload)
(defoverload workloads/update-workload!    pipeline update-covid-workload)
(defoverload workloads/stop-workload!      pipeline stop-covid-workload)
(defoverload workloads/retry               pipeline batch/retry-unsupported)
(defoverload workloads/load-workload-impl  pipeline load-covid-workload-impl)
(defmethod   workloads/workflows           pipeline
  [tx {:keys [executor] :as _workload}]
  (executor/executor-workflows tx executor))
(defmethod   workloads/workflows-by-status pipeline
  [tx {:keys [executor] :as _workload} status]
  (executor/executor-workflows-by-status tx executor status))
(defoverload workloads/to-edn             pipeline workload-to-edn)

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
    (firecloud/workspace-or-throw workspace)
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

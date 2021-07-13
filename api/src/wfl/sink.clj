(ns wfl.sink
  "Workload sink interface and its implementations."
  (:require [clojure.data          :as data]
            [clojure.edn           :as edn]
            [clojure.set           :as set]
            [clojure.string        :as str]
            [clojure.tools.logging :as log]
            [wfl.api.workloads     :refer [defoverload]]
            [wfl.jdbc              :as jdbc]
            [wfl.service.datarepo  :as datarepo]
            [wfl.service.firecloud :as firecloud]
            [wfl.service.postgres  :as postgres]
            [wfl.service.rawls     :as rawls]
            [wfl.stage             :as stage]
            [wfl.util              :as util :refer [utc-now]])
  (:import [clojure.lang ExceptionInfo]
           [org.apache.commons.lang3 NotImplementedException]
           [wfl.util UserException]))

;; Interface
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

(defmulti load-sink!
  "Return the `Sink` implementation associated with the `sink_type` and
   `sink_items` fields of the `workload` row in the database.
   Note that this multimethod is type-dispatched on the `:sink_type`
   association in the `workload`."
  (fn [_transaction workload] (:sink_type workload)))

(defmulti update-sink!
  "Update the internal state of the `sink`, consuming objects from the
   Queue `upstream-queue`, performing any external effects as required.
   Implementations should avoid maintaining in-memory state and making long-
   running external calls, favouring internal queues to manage such tasks
   asynchronously between invocations.
   Note that the `Sink` and `Queue` are parameterised types
   and the `Queue`'s parameterisation must be convertible to the `Sink`s."
  (fn [_upstream-queue sink] (:type sink)))

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

(def terra-workspace-malformed-from-outputs-message
  (str/join " " ["fromOutputs must define a mapping from workflow outputs"
                 "to the attributes of entityType."]))

(def unknown-attributes-error-message
  (str/join " " ["Found additional attributes in fromOutputs that are not"
                 "present in the entityType."]))

(defn ^:private terra-workspace-validate-request-or-throw
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
  (when-let [[_ {:keys [uuid outputs] :as workflow}] (stage/peek-queue executor)]
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

(defoverload stage/validate-or-throw terra-workspace-sink-name terra-workspace-validate-request-or-throw)
(defoverload create-sink!            terra-workspace-sink-name create-terra-workspace-sink)

(defoverload load-sink!    terra-workspace-sink-type load-terra-workspace-sink)
(defoverload update-sink!  terra-workspace-sink-type update-terra-workspace-sink)
(defoverload stage/done?   terra-workspace-sink-type terra-workspace-sink-done?)

(defoverload util/to-edn terra-workspace-sink-type terra-workspace-sink-to-edn)

;; TerraDataRepo Sink
(def ^:private datarepo-sink-name  "Terra DataRepo Sink")
(def ^:private datarepo-sink-type  "TerraDataRepoSink")
(def ^:private datarepo-sink-table "TerraDataRepoSink")
(def ^:private datarepo-sink-serialized-fields
  {:dataset     :dataset
   :table       :dataset_table
   :fromOutputs :from_outputs})

(defn ^:private create-datarepo-sink [tx id request]
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

(defn ^:private datarepo-sink-validate-request-or-throw
  "Throw unless the user's sink `request` yields a valid configuration for a
   TerraDataRepoSink by ensuring all resources specified in the request exist."
  [{:keys [dataset table fromOutputs] :as request}]
  (let [dataset' (datarepo/dataset dataset)
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

(defn ^:private update-datarepo-sink
  [_executor _sink]
  (throw (NotImplementedException. "update-datarepo-sink")))

(defn ^:private datarepo-sink-done? [{:keys [details] :as _sink}]
  (let [query "SELECT COUNT(*) FROM %s WHERE consumed IS NOT NULL"]
    (jdbc/with-db-transaction [tx (postgres/wfl-db-config)]
      (postgres/throw-unless-table-exists tx details)
      (-> (jdbc/query tx (format query details)) first :count zero?))))

(defn ^:private datarepo-sink-to-edn [sink]
  (-> sink
      (util/select-non-nil-keys (keys datarepo-sink-serialized-fields))
      (assoc :name datarepo-sink-name)))

(defoverload stage/validate-or-throw datarepo-sink-name datarepo-sink-validate-request-or-throw)
(defoverload create-sink!            datarepo-sink-name create-datarepo-sink)

(defoverload load-sink!   datarepo-sink-type load-datarepo-sink)
(defoverload update-sink! datarepo-sink-type update-datarepo-sink)
(defoverload stage/done?  datarepo-sink-type datarepo-sink-done?)

(defoverload util/to-edn datarepo-sink-type datarepo-sink-to-edn)

(ns wfl.module.covid
  "Manage the Sarscov2IlluminaFull pipeline."
  (:require [clojure.edn :as edn]
            [clojure.set :as set]
            [wfl.api.workloads :as workloads :refer [defoverload]]
            [wfl.jdbc :as jdbc]
            [wfl.module.batch :as batch]
            [wfl.service.postgres :as postgres]
            [wfl.service.rawls :as rawls]
            [wfl.util :as util]
            [wfl.wfl :as wfl]
            [clojure.data.json :as json]
            [clojure.string :as str]
            [clojure.tools.logging :as log])
  (:import [java.time OffsetDateTime]
           [java.util UUID]))

(def pipeline "Sarscov2IlluminaFull")

(defn ^:private get-snapshots-from-workspace
  [workspace])

(defn start-covid-workload
  "Mark WORKLOAD with a started timestamp."
  [tx {:keys [id started] :as workload}]
  (jdbc/update! tx :workload {:started (OffsetDateTime/now)} ["id = ?" id])
  (workloads/load-workload-for-id tx id))

(defn ^:private get-imported-snapshot-reference
  "Nil or the snapshot reference for SNAPSHOT_REFERENCE_ID in WORKSPACE."
  [{:keys [workspace] :as _executor}
   {:keys [snapshot_reference_id] :as _executor_details}]
  (when snapshot_reference_id
    (util/do-or-nil (rawls/get-snapshot-reference workspace
                                                  snapshot_reference_id))))

(defn ^:private import-snapshot!
  "Import snapshot with SNAPSHOT_ID to WORKSPACE.
  Use transaction TX to update DETAILS table with resulting reference id."
  [tx
   workload
   {:keys [snapshot_id] :as _source_details}
   {:keys [workspace details] :as _executor}
   executor_details]
  (let [refid (-> (rawls/create-snapshot-reference workspace snapshot_id)
                  :referenceId)]
    (jdbc/update! tx
                  details
                  {:snapshot_reference_id refid}
                  ["id = ?" (:id executor_details)])
    (jdbc/update! tx
                  :workload
                  {:updated (OffsetDateTime/now)}
                  ["id = ?" (:id workload)])))

;; Generic helpers
(defn ^:private load-record-by-id! [tx table id]
  (let [query        "SELECT * FROM %s WHERE id = ? LIMIT 1"
        [record & _] (jdbc/query tx [(format query table) id])]
    (when-not record
      (throw (ex-info (str "No such record") {:id id :table table})))
    record))

;; interfaces
;; queue operations
(defmulti peek-queue!
  "Peek the first object from the `queue`, if one exists."
  (fn [queue] (:type queue)))

(defmulti pop-queue!
  "Pop the first object from the `queue`. Throws if none exists."
  (fn [queue] (:type queue)))

;; source operations
(defmulti create-source!
  "Use `tx` and workload `id` to write the source to persisted storage and
   return a [type item] pair to be written into the parent table."
  (fn [tx id source-request] (:name source-request)))

(defmulti update-source!
  "Update the source."
  (fn [source] (:type source)))

(defmulti load-source!
  "Use `tx` to load the workload source with `source_type`."
  (fn [tx workload] (:source_type workload)))

;; executor operations
(defmulti create-executor!
  "Use `tx` and workload `id` to write the executor to persisted storage and
   return a [type item] pair to be written into the parent table."
  (fn [tx id executor-request] (:name executor-request)))

(defmulti update-executor!
  "Update the executor with the `source`"
  (fn [source executor] (:type executor)))

(defmulti load-executor!
  "Use `tx` to load the workload executor with `executor_type`."
  (fn [tx workload] (:executor_type workload)))

;; sink operations
(defmulti create-sink!
  "Use `tx` and workload `id` to write the sink to persisted storage and
   return a [type item] pair to be written into the parent table."
  (fn [tx id sink-request] (:name sink-request)))

(defmulti update-sink!
  "Update the sink with the `executor`"
  (fn [executor sink] (:type sink)))

(defmulti load-sink!
  "Use `tx` to load the workload sink with `sink_type`."
  (fn [tx workload] (:sink_type workload)))

(defn ^:private add-continuous-workload-record
  "Use `tx` and workload `id` to create a \"ContinuousWorkload\" instance and
  return the ID of the ContinuousWorkload."
  [tx id {:keys [source sink executor] :as _request}]
  (let [set-details "UPDATE
                         ContinuousWorkload
                     SET
                         source_type   = ?::source,
                         executor_type = ?::executor,
                         sink_type     = ?::sink
                     WHERE
                         id = ? "
        src-exc-snk [(create-source! tx id source)
                     (create-executor! tx id executor)
                     (create-sink! tx id sink)]
        items       (->> (map second src-exc-snk)
                         (zipmap [:source_items :executor_items :sink_items])
                         (jdbc/insert! tx :ContinuousWorkload)
                         first
                         :id)]
    (jdbc/execute! tx (concat [set-details] (map first src-exc-snk) [items]))
    items))

(defn ^:private add-workload-record
  "Use `tx` to create a workload `record` for `request` and return the id of the
   new workload."
  [tx request]
  (letfn [(combine-labels [labels]
            (->> (mapv request [:pipeline :project])
                 (map str ["pipeline:" "project:"])
                 (concat labels)
                 set
                 sort
                 vec))]
    (-> (update request :labels combine-labels)
        (select-keys [:creator :watchers :labels :project])
        (merge (select-keys (wfl/get-the-version) [:commit :version]))
        (assoc :executor "" :output "" :release "" :wdl "" :uuid (UUID/randomUUID))
        (->> (jdbc/insert! tx :workload) first :id))))

;; TODO: validate the request before creating the workload
(defn create-covid-workload [tx request]
  (let [set-pipeline "UPDATE workload
                      SET pipeline = ?::pipeline
                      WHERE id = ?"
        id           (add-workload-record tx request)
        items        (add-continuous-workload-record tx id request)]
    (jdbc/execute! tx [set-pipeline pipeline id])
    (jdbc/update! tx :workload {:items items} ["id = ?" id])
    (workloads/load-workload-for-id tx id)))

(defn update-covid-workload
  "Use transaction TX to update WORKLOAD statuses."
  [tx {:keys [started finished] :as workload}]
  (letfn [(update-workload-status [])
          (update! [{:keys [id source executor sink] :as _workload}]
            (-> (update-source! source)
                (update-executor! executor)
                (update-sink! sink))
            (update-workload-status)
            (workloads/load-workload-for-id tx id))]
    (if (and started (not finished)) (update! workload) workload)))

(defn load-covid-workload-impl [tx {:keys [items] :as workload}]
  (if-let [id (util/parse-int items)]
    (let [details (load-record-by-id! tx "ContinuousWorkload" id)]
      (->> {:source   (load-source! tx details)
            :executor (load-executor! tx details)
            :sink     (load-sink! tx details)}
           (merge workload)
           (filter second)
           (into {})))
    (throw (ex-info "Invalid ContinuousWorkload identifier"
                    {:id       items
                     :workload workload}))))

;; Terra Data Repository Source
(def ^:private tdr-source-name  "Terra DataRepo")
(def ^:private tdr-source-type  "TerraDataRepoSource")
(def ^:private tdr-source-table "TerraDataRepoSource")
(def ^:private tdr-source-serialized-fields
  {:dataset :dataset
   :table   :dataset_table
   :column  :table_column_name})

;; Create and add new snapshots to the snapshot queue
(defn ^:private update-tdr-source [source]
  (letfn [(find-new-rows       [source now] [])
          (make-snapshots      [source row-ids])
          (write-snapshots     [source snapshots])
          (update-last-checked [source now])]
    (let [now     (OffsetDateTime/now)
          row-ids (find-new-rows source now)]
      (when-not (empty? row-ids)
        (write-snapshots source (make-snapshots source row-ids)))
      (update-last-checked source now))))

(defn ^:private load-tdr-source [tx {:keys [source_items] :as details}]
  (if-let [id (util/parse-int source_items)]
    (-> (load-record-by-id! tx tdr-source-table id)
        (assoc :type tdr-source-type)
        (set/rename-keys (set/map-invert tdr-source-serialized-fields)))
    (throw (ex-info "source_items is not an integer" details))))

(defn ^:private create-tdr-source [tx id request]
  (let [create  "CREATE TABLE %s OF TerraDataRepoSourceDetails (PRIMARY KEY (id))"
        details (format "TerraDataRepoSourceDetails_%09d" id)
        _       (jdbc/execute! tx [(format create details)])
        items   (-> (select-keys request (keys tdr-source-serialized-fields))
                    (set/rename-keys tdr-source-serialized-fields)
                    (assoc :details details)
                    (->> (jdbc/insert! tx tdr-source-table)))]
    [tdr-source-type (-> items first :id str)]))

(defn ^:private peek-tdr-source-queue [{:keys [queue] :as _source}]
  "Get first unvisited source in QUEUE."
  (let [query "SELECT * FROM %s WHERE NOT visited ORDER BY id ASC LIMIT 1"]
    (jdbc/with-db-transaction [tx (postgres/wfl-db-config)]
      (first (jdbc/query tx (format query queue))))))

(defn ^:private pop-tdr-source-queue [{:keys [queue] :as source}]
  "Mark first unvisited source in QUEUE as visited, or throw if empty."
  (if-let [{:keys [id] :as _snapshot} (peek-queue! source)]
    (jdbc/with-db-transaction [tx (postgres/wfl-db-config)]
      (jdbc/update! tx queue {:visited true} ["id = ?" id]))
    (throw (ex-info "No snapshots in queue" {:source source}))))

(defoverload create-source! tdr-source-name create-tdr-source)
(defoverload peek-queue!    tdr-source-type peek-tdr-source-queue)
(defoverload pop-queue!     tdr-source-type pop-tdr-source-queue)
(defoverload update-source! tdr-source-type update-tdr-source)
(defoverload load-source!   tdr-source-type load-tdr-source)

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
        details (format "TerraExecutorDetails_%09d" id)
        _       (jdbc/execute! tx [(format create details)])
        items   (-> (select-keys request (keys terra-executor-serialized-fields))
                    (update :fromSource pr-str)
                    (set/rename-keys terra-executor-serialized-fields)
                    (assoc :details details)
                    (->> (jdbc/insert! tx terra-executor-table)))]
    [terra-executor-type (-> items first :id str)]))

(defn ^:private load-terra-executor [tx {:keys [executor_items] :as details}]
  (if-let [id (util/parse-int executor_items)]
    (-> (load-record-by-id! tx terra-executor-table id)
        (assoc :type terra-executor-type)
        (set/rename-keys (set/map-invert terra-executor-serialized-fields))
        (update :fromSource edn/read-string))
    (throw (ex-info "Invalid executor_items" details))))

(defn ^:private find-new-snapshots
  "Return source details records for first member of SOURCE queue."
  [source]
  (if-let [source-entry (peek-queue! source)]
    (jdbc/with-db-transaction [tx (postgres/wfl-db-config)]
      (->> (:details source-entry)
           (format "SELECT * FROM %s")
           (jdbc/query tx)))))

(defn ^:private import-snapshots!
  "Create and return snapshot references for all SNAPSHOTS in WORKSPACE."
  [{:keys [workspace] :as _executor}
   snapshots]
  {:pre [(seq snapshots)]}
  (letfn [(import [snapshot]
            (->> (:snapshot_id snapshot)
                 (rawls/create-snapshot-reference workspace)))]
    (map import snapshots)))

(defn ^:private update-terra-executor [source executor]
  "IN PROGRESS:
  Create and add new submissions to the submission queue from new SOURCE snapshots.
  (Should) return updated EXECUTOR."
  (letfn [(create-submissions  [executor snapshot-references]
            ;; Tom to implement?
            ;; Create submissions from snapshot references.
            ;; Pop source, coerce to executor with executor.fromSource,
            ;; and push to executor queue --
            ;; i.e. write to executor (and corresponding details) tables
            ;; Return updated executor.
            )]
    (let [now      (OffsetDateTime/now) ; Use to update... something?
          snapshots (find-new-snapshots source)]
      (when (seq snapshots)
        (create-submissions executor (import-snapshots! executor snapshots))))))

(defn ^:private peek-terra-executor-queue [{:keys [queue] :as _executor}]
  "Get first unvisited executor in QUEUE."
  (let [query "SELECT * FROM %s WHERE NOT visited ORDER BY id ASC LIMIT 1"]
    (jdbc/with-db-transaction [tx (postgres/wfl-db-config)]
      (first (jdbc/query tx (format query queue))))))

(defn ^:private pop-terra-executor-queue [{:keys [queue] :as executor}]
  "Mark first unvisited executor in QUEUE as visited, or throw if empty."
  (if-let [{:keys [id] :as _submission} (peek-queue! executor)]
    (jdbc/with-db-transaction [tx (postgres/wfl-db-config)]
      (jdbc/update! tx queue {:visited true} ["id = ?" id]))
    (throw (ex-info "No submissions in queue" {:executor executor}))))

(defoverload create-executor! terra-executor-name create-terra-executor)
(defoverload load-executor! terra-executor-type load-terra-executor)
(defoverload update-executor! terra-executor-type update-terra-executor)
(defoverload peek-queue! terra-executor-type peek-terra-executor-queue)
(defoverload pop-queue! terra-executor-type pop-terra-executor-queue)

;; Terra Workspace Sink
(def ^:private terra-workspace-sink-name  "Terra Workspace")
(def ^:private terra-workspace-sink-type  "TerraWorkspaceSink")
(def ^:private terra-workspace-sink-table "TerraWorkspaceSink")
(def ^:private terra-workspace-sink-serialized-fields
  {:workspace   :workspace
   :entity      :entity
   :fromOutputs :from_outputs
   :identifier  :identifier})

(defn ^:private create-terra-workspace-sink [tx id request]
  (let [create  "CREATE TABLE %s OF TerraWorkspaceSinkDetails (PRIMARY KEY (id))"
        details (format "TerraWorkspaceSinkDetails_%09d" id)
        _       (jdbc/execute! tx [(format create details)])
        items   (-> (select-keys request (keys terra-workspace-sink-serialized-fields))
                    (update :fromOutputs pr-str)
                    (set/rename-keys terra-workspace-sink-serialized-fields)
                    (assoc :details details)
                    (->> (jdbc/insert! tx terra-workspace-sink-table)))]
    [terra-workspace-sink-type (-> items first :id str)]))

(defn ^:private load-terra-workspace-sink
  [tx {:keys [sink_items] :as details}]
  (if-let [id (util/parse-int sink_items)]
    (-> (load-record-by-id! tx terra-workspace-sink-table id)
        (set/rename-keys (set/map-invert terra-workspace-sink-serialized-fields))
        (update :fromOutputs edn/read-string)
        (assoc :type terra-workspace-sink-type))
    (throw (ex-info "Invalid sink_items" details))))

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

(defn ^:private update-terra-workspace-sink
  [executor {:keys [fromOutputs workspace entity identifier details] :as _sink}]
  (when-let [{:keys [uuid outputs] :as workflow} (peek-queue! executor)]
    (log/debug "Coercing workflow" uuid "outputs to" entity)
    (let [attributes (terra-workspace-sink-to-attributes workflow fromOutputs)
          name       (outputs (keyword identifier))]
      (log/debug "Upserting workflow" uuid "outputs as" name)
      (rawls/batch-upsert workspace [[name entity attributes]])
      (pop-queue! executor)
      (log/info "Sank workflow" uuid "as" name)
      (jdbc/with-db-transaction [tx (postgres/wfl-db-config)]
        (->> {:entity_name name
              :workflow    uuid
              :updated     (OffsetDateTime/now)
              :id          (inc (postgres/table-max tx details :id))}
             (jdbc/insert! tx details))))))

(defoverload create-sink! terra-workspace-sink-name create-terra-workspace-sink)
(defoverload load-sink!   terra-workspace-sink-type load-terra-workspace-sink)
(defoverload update-sink! terra-workspace-sink-type update-terra-workspace-sink)

(defoverload workloads/create-workload! pipeline create-covid-workload)
(defoverload workloads/start-workload! pipeline start-covid-workload)
(defoverload workloads/update-workload! pipeline update-covid-workload)
(defoverload workloads/stop-workload! pipeline batch/stop-workload!)
(defoverload workloads/load-workload-impl pipeline load-covid-workload-impl)

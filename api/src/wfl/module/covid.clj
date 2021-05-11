(ns wfl.module.covid
  "Manage the Sarscov2IlluminaFull pipeline."
  (:require [clojure.data.json :as json]
            [clojure.set :as set]
            [clojure.string :as str]
            [clojure.tools.logging.readable :as log]
            [wfl.api.workloads :as workloads :refer [defoverload]]
            [wfl.jdbc :as jdbc]
            [wfl.module.batch :as batch]
            [wfl.service.datarepo :as datarepo]
            [wfl.service.firecloud :as firecloud]
            [wfl.service.google.storage :as gcs]
            [wfl.service.postgres :as postgres]
            [wfl.service.rawls :as rawls]
            [wfl.util :as util]
            [wfl.wfl :as wfl]
            [wfl.debug :as debug]
            [clojure.pprint :as pprint]
            [fipp.edn :as edn])
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

(defn ^:private create-continuous-workload-record
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

(defn ^:private add-workload-record [tx request]
  "Use `tx` to create a workload `record` for `request` and return the id of the
   new workload."
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

;; TODO: implement COVID workload creation
;;  - make sure permissions/inputs are right upfront
(defn create-covid-workload [tx request]
  (let [set-pipeline "UPDATE workload
                      SET pipeline = ?::pipeline
                      WHERE id = ?"
        id           (add-workload-record tx request)
        items        (create-continuous-workload-record tx id request)]
    (jdbc/execute! tx [set-pipeline pipeline id])
    (jdbc/update!  tx :workload {:items items} ["id = ?" id])
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
  (let [details (->> (util/parse-int items)
                     (conj ["SELECT * FROM ContinuousWorkload WHERE id = ?"])
                     (jdbc/query tx)
                     first)]
    (->> {:source   (load-source! tx details)
          :executor (load-executor! tx details)
          :sink     (load-sink! tx details)}
         (merge workload)
         (filter second)
         (into {}))))

(def tdr-source-name "Terra DataRepo")
(def tdr-source-type "TerraDataRepoSource")

;; Note I've used a table to implement the queue and I'm deleting the row
;; when it's popped.
;;
;; You could just mark it as :visited or something.
(defn ^:private peek-tdr-source-queue [{:keys [queue] :as _source}]
  (let [query "SELECT * FROM %s ORDER BY id ASC LIMIT 1"]
    (jdbc/with-db-transaction [tx (postgres/wfl-db-config)]
      (first (jdbc/query tx (format query queue))))))

(defn ^:private pop-tdr-source-queue [{:keys [queue] :as source}]
  (if-let [{:keys [id] :as _snapshot} (peek-queue! source)]
    (let [query "DELETE FROM %s WHERE id = ?"]
      (jdbc/with-db-transaction [tx (postgres/wfl-db-config)]
        (jdbc/execute! tx [(format query queue) id])))
    (throw (ex-info "No snapshots in queue" {:source source}))))

;; Create and add new snapshots to the snapshot queue
(defn ^:private update-tdr-source [source]
  (letfn [(find-new-rows       [source now]      [])
          (make-snapshots      [source row-ids])
          (write-snapshots     [source snapshots])
          (update-last-checked [now])]
    (let [now     (OffsetDateTime/now)
          row-ids (find-new-rows source now)]
      (when-not (empty? row-ids)
        (write-snapshots source (make-snapshots source row-ids)))
      (update-last-checked source now))))

(defn ^:private load-tdr-source [tx {:keys [source_items] :as details}]
  (if-let [source (->> (util/parse-int source_items)
                       (conj ["SELECT * FROM TerraDataRepoSource WHERE id = ? LIMIT 1"])
                       (jdbc/query tx)
                       first)]
    (assoc source :type tdr-source-type)
    (throw (ex-info (str "No source matching id " source_items) details))))

;; maybe we want to split check-request and create to avoid writing
;; records for bad workload requests?
(defn ^:private create-tdr-source [tx id request]
  (let [create    "CREATE TABLE %s OF TerraDataRepoSourceDetails (PRIMARY KEY (id))"
        details   (format "%sDetails_%09d" tdr-source-type id)
        _         (jdbc/execute! tx [(format create details)])
        req->cols {:dataset :dataset
                   :table   :dataset_table
                   :column  :table_column_name}
        items     (-> (select-keys request (keys req->cols))
                      (set/rename-keys req->cols)
                      (assoc :details details)
                      (->> (jdbc/insert! tx tdr-source-type)))]
    [tdr-source-type (-> items first :id)]))

(defoverload create-source! tdr-source-name create-tdr-source)
(defoverload peek-queue!    tdr-source-type peek-tdr-source-queue)
(defoverload pop-queue!     tdr-source-type pop-tdr-source-queue)
(defoverload update-source! tdr-source-type update-tdr-source)
(defoverload load-source!   tdr-source-type load-tdr-source)

(def ^:private terra-executor-name "Terra")
(def ^:private terra-executor-type "TerraExecutor")

(defn ^:private create-terra-executor [tx id request]
  (let [create   "CREATE TABLE %s OF TerraExecutorDetails (PRIMARY KEY (id))"
        details  (format "%sDetails_%09d" terra-executor-type id)
        _        (jdbc/execute! tx [(format create details)])
        req->cols {:workspace                   :workspace
                   :methodConfiguration         :method_configuration
                   :methodConfigurationVersion  :method_configuration_version
                   :fromSource                  :from_source}
        items     (-> (select-keys request (keys req->cols))
                      (set/rename-keys req->cols)
                      (assoc :details details)
                      (update :from_source pr-str)
                      (->> (jdbc/insert! tx terra-executor-type)))]
    [terra-executor-type (-> items first :id)]))

(defn ^:private load-terra-executor [tx {:keys [executor_items] :as details}]
  (if-let [executor (->> (util/parse-int executor_items)
                         (conj ["SELECT * FROM TerraExecutor WHERE id = ? LIMIT 1"])
                         (jdbc/query tx)
                         first)]
    (assoc executor :type terra-executor-type)
    (throw (ex-info (str "No executor matching id " executor_items) details))))

(defoverload create-executor! terra-executor-name create-terra-executor)
(defoverload load-executor!   terra-executor-type load-terra-executor)

(def ^:private terra-workspace-sink-name "Terra Workspace")
(def ^:private terra-workspace-sink-type "TerraWorkspaceSink")

(defn ^:private create-terra-workspace-sink [tx id request]
  (let [create   "CREATE TABLE %s OF TerraDataRepoSourceDetails (PRIMARY KEY (id))"
        details  (format "%sDetails_%09d" terra-workspace-sink-type id)
        _        (jdbc/execute! tx [(format create details)])
        req->cols {:workspace                   :workspace
                   :entity                      :entity
                   :fromOutputs                 :from_outputs}
        items     (-> (select-keys request (keys req->cols))
                      (set/rename-keys req->cols)
                      (assoc :details details)
                      (update :from_outputs pr-str)
                      (->> (jdbc/insert! tx terra-workspace-sink-type)))]
    [terra-workspace-sink-type (-> items first :id)]))

(defn ^:private load-terra-workspace-sink
  [tx {:keys [sink_items] :as details}]
  (if-let [sink (->> (util/parse-int sink_items)
                     (conj ["SELECT * FROM TerraWorkspaceSink WHERE id = ? LIMIT 1"])
                     (jdbc/query tx)
                     first)]
    (assoc sink :type terra-executor-type)
    (throw (ex-info (str "No sink matching id " sink_items) details))))

(defoverload create-sink! terra-workspace-sink-name create-terra-workspace-sink)
(defoverload load-sink!   terra-workspace-sink-type load-terra-workspace-sink)

(defoverload workloads/create-workload!   pipeline create-covid-workload)
(defoverload workloads/start-workload!    pipeline start-covid-workload)
(defoverload workloads/update-workload!   pipeline update-covid-workload)
(defoverload workloads/stop-workload!     pipeline batch/stop-workload!)
(defoverload workloads/load-workload-impl pipeline load-covid-workload-impl)

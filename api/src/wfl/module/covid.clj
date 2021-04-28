(ns wfl.module.covid
  "Handle COVID processing."
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
            [wfl.wfl :as wfl]))

(def pipeline "Sarscov2IlluminaFull")

;; TODO: implement COVID workload creation
;;  - make sure permissions/inputs are right upfront
;;  - dispatch on source/sink/executor
;;  - store information into database
(defn create-covid-workload!
  [tx {:keys [source sink executor] :as request}])

(defn start-covid-workload!
  [])

(defn ^:private snapshot-error-map
  "Return default ExceptionInfo map for snapshot-related exceptions."
  [{:keys [items sink] :as _workload} {:keys [id snapshot_id] :as _record}]
  {:workspace (:workspace sink)
   :table items
   :id id
   :snapshot_id snapshot_id})

(defn ^:private try-get-snapshot-reference
  "Attempt to fetch snapshot reference with id SNAPSHOT_REFERENCE_ID
  from SINK-derived workspace."
  [{:keys [sink] :as workload} {:keys [snapshot_reference_id] :as record}]
  (let [workspace (:workspace sink)]
    (try
      (rawls/get-snapshot-reference workspace snapshot_reference_id)
      (catch Throwable cause
        (throw (ex-info "Rawls unable to fetch snapshot reference"
                        (util/deep-merge
                         (snapshot-error-map workload record)
                         {:snapshot_reference_id snapshot_reference_id})
                        cause))))))

(defn ^:private snapshot-imported?
  "Check if snapshot with id SNAPSHOT_ID in TDR has been imported to
  SINK-derived workspace and can be successfully fetched."
  [workload {:keys [snapshot_id snapshot_reference_id] :as record}]
  {:pre [(some? snapshot_id)]}
  (and (some? snapshot_reference_id)
       (some? (try-get-snapshot-reference workload record))))

(defn ^:private import-snapshot!
  "Import snapshot with id SNAPSHOT_ID from TDR to workspace.
  Use transaction TX to update ITEMS table with resulting reference id."
  [tx {:keys [items sink] :as workload} {:keys [id snapshot_id] :as record}]
  (let [workspace (:workspace sink)
        name (util/randomize "placeholder-snapshot-ref-name")
        reference-id (rawls/create-snapshot-reference workspace snapshot_id name)]
    (when (nil? reference-id)
      (throw (ex-info "Rawls unable to create snapshot reference"
                      (util/deep-merge (snapshot-error-map workload record)
                                       {:name name}))))
    (jdbc/update! tx items {:snapshot_reference_id reference-id} ["id = ?" id])))

(comment "Proposed workload update loop as described in Spike doc for reference"
         (cond
           (workflow-finished?) (mark-as-done!)
           (submission-launched?) (monitor-workflow!)
           (snapshot-imported?) (launch-submission!)
           (snapshot-created?) (import-snapshot!)
           (snapshot-creation-job-exist?) (monitor-snapshot-status!)
           :else (check-for-new-data-in-TDR!)))

;; TODO: implement progressive (private) functions inside this update loop
(defn update-covid-workload!
  "Use transaction `tx` to batch-update `workload` statuses."
  [tx {:keys [started finished] :as workload}]
  (letfn [(update! [{:keys [id] :as workload}]
            (postgres/batch-update-workflow-statuses! tx workload)
            (postgres/update-workload-status! tx workload)
            (workloads/load-workload-for-id tx id))]
    (if (and started (not finished)) (update! workload) workload)))

(defoverload workloads/create-workload!   pipeline create-covid-workload!)
(defoverload workloads/start-workload!    pipeline start-covid-workload!)
(defoverload workloads/update-workload!   pipeline update-covid-workload!)
(defoverload workloads/stop-workload!     pipeline batch/stop-workload!)
(defoverload workloads/load-workload-impl pipeline
  batch/load-batch-workload-impl)

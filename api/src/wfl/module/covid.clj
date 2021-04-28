(ns wfl.module.covid
  "Handle COVID processing."
  (:require [clojure.data.json :as json]
            [clojure.set :as set]
            [clojure.string :as str]
            [clojure.tools.logging.readable :as log]
            [wfl.api.workloads :as workloads :refer [defoverload]]
            [wfl.jdbc :as jdbc]
            [wfl.module.batch :as batch]
            [wfl.service.google.bigquery :as bigquery]
            [wfl.service.datarepo :as datarepo]
            [wfl.service.firecloud :as firecloud]
            [wfl.service.google.storage :as gcs]
            [wfl.service.postgres :as postgres]
            [wfl.util :as util]
            [wfl.wfl :as wfl]))

(def pipeline "Sarscov2IlluminaFull")

;; TODO: implement COVID workload creation
;;  - make sure permissions/inputs are right upfront
;;  - dispatch on source/sink/executor
;;  - store information into database
(defn create-covid-workload!
  [tx {:keys [source sink executor] :as request}])

(defn ^:private snapshot-created?
  "Check if snapshot has been created in TDR, return snapshot id if so."
  [{:keys [snapshot_job_id] :as _record}]
  (->> snapshot_job_id
       (datarepo/job-done?)
       :id))

(defn ^:private go-create-snapshot!
  "Create snapshot in TDR from `dataset`, `table`
   and `row-ids` and write into `items` table of
   WFL database."
  [tx dataset table items row-ids]
  (let [columns     (-> (datarepo/all-columns dataset table)
                        (->> (map :name) set)
                        (conj "datarepo_row_id"))
        job-id (-> (datarepo/make-snapshot-request dataset columns table row-ids)
                   (update :name util/randomize)
                   ;; FIXME: this sometimes runs into: Cannot update iam permissions,
                   ;;        need failure handling here!
                   (datarepo/create-snapshot))]

    (jdbc/insert! tx items {:dataset_id (:id dataset)
                            :snapshot_job_id job-id})))

(defn ^:private check-for-new-data
  [tx {:keys [source sink executor] :as workload}]
  (let [tx ""
        most-recent-record-date "2021-03-29 00:00:00"
        now (util/utc-now)
        dataset (datarepo/dataset "ff6e2b40-6497-4340-8947-2f52a658f561")
        table "flowcell"
        col-name "updated"
        row-ids (-> (datarepo/query-table-between
                     dataset
                     table
                     col-name
                     [most-recent-record-date now]
                     [:datarepo_row_id])
                    :rows
                    flatten)
        items (:items workload)]
    (when row-ids
      (let [shards (partition-all 500 row-ids)]
        (map #(go-create-snapshot! tx dataset table items %) shards)))))

(defn update-covid-workload!
  "Use transaction `tx` to batch-update `workload` statuses."
  [tx {:keys [started finished] :as workload}]
  (letfn [(update! [{:keys [id] :as workload}]
            (postgres/batch-update-workflow-statuses! tx workload)
            (postgres/update-workload-status! tx workload)
            (workloads/load-workload-for-id tx id))]
    (if (and started (not finished)) (update! workload) workload)))

(defoverload workloads/create-workload!   pipeline create-covid-workload!)
(defoverload workloads/start-workload!    pipeline batch/stop-workload!)
(defoverload workloads/update-workload!   pipeline update-covid-workload!)
(defoverload workloads/stop-workload!     pipeline batch/stop-workload!)
(defoverload workloads/load-workload-impl pipeline
  batch/load-batch-workload-impl)

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
  "Check TDR job to see if the snapshot is created successfully yet."
  []
  ;; TODO: Poll TDR for job status
  )

(defn ^:private check-for-new-data-in
  [tx {:keys [dataset] :as workload}]
  (let [dataset "ff6e2b40-6497-4340-8947-2f52a658f561"]
    ))

(comment
  (defn go-create-snapshot!
    [tx row-ids]
    (let [columns     (-> (datarepo/all-columns dataset table)
                        (->> (map :name) set)
                        (conj "datarepo_row_id"))
          job-id (-> (datarepo/make-snapshot-request dataset columns table row-ids)
                   (update :name util/randomize)
                   (datarepo/create-snapshot))]
      ;; TODO: Update database
      ; (jdbc/insert! tx )
      ;; TODO: Failure handling
      ))

  ;; Partition row IDs into batches of 500 to keep TDR happy.
  ;; Ask Ruchi for the reference to the bug ticket if there's one.
  (let [most-recent-record-date "2021-03-29 00:00:00"
        ;; FIXME: ask TDR/GP if the `updated` is local or UTC timestamp
        local-now (.format (java.time.format.DateTimeFormatter/ofPattern "yyyy-MM-dd HH:mm:ss") (java.time.OffsetDateTime/now))
        dataset (datarepo/dataset "ff6e2b40-6497-4340-8947-2f52a658f561")]
        table "flowcell"
        col-name "updated"
        row-ids (-> (datarepo/query-table-between
                      dataset
                      table
                      col-name
                      [most-recent-record-date local-now]
                      [:datarepo_row_id])
                  :rows
                  flatten)
    (when row-ids
      (let [shards (partition-all 500 row-ids)]
        (map go-create-snapshot! shards))))
  )

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
(defoverload workloads/start-workload!    pipeline batch/stop-workload!)
(defoverload workloads/update-workload!   pipeline update-covid-workload!)
(defoverload workloads/stop-workload!     pipeline batch/stop-workload!)
(defoverload workloads/load-workload-impl pipeline
  batch/load-batch-workload-impl)

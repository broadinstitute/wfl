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
            [wfl.util :as util]
            [wfl.wfl :as wfl])
  (:import (java.time OffsetDateTime)))

(def pipeline "Sarscov2IlluminaFull")

;; TODO: implement COVID workload creation
;;  - make sure permissions/inputs are right upfront
;;  - dispatch on source/sink/executor
;;  - store information into database
(defn create-covid-workload!
  [tx {:keys [source sink executor] :as request}])

(defn start-covid-workload!
  [])

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

(defmulti peek-queue
  "Peek the first object from the `queue`, if one exists."
  (fn [queue] (:type queue)))

(defmulti pop-queue
  "Pop the first object from the `queue`. Throws if none exists."
  (fn [queue] (:type queue)))

;; source operations
(defmulti update-source
  "Update the source."
  (fn [source] (:type source)))

(defmulti load-source!
  "Load the workload `source`."
  (fn [workload] (:source-type workload)))

;; executor operations
(defmulti update-executor!
  "Update the executor with the `source`"
  (fn [source executor] (:type executor)))

(defmulti load-executor!
  "Load the workload `executor`."
  (fn [workload] (:executor-type workload)))

;; sink operations
(defmulti update-sink!
  "Update the sink with the `executor`"
  (fn [executor sink] (:type sink)))

(defmulti load-sink!
  "Load the workload `sink`."
  (fn [workload] (:sink-type workload)))

(defn update-covid-workload
  "Use transaction TX to update WORKLOAD statuses."
  [tx {:keys [started finished] :as workload}]
  (letfn [(update-workload-status [])
          (update! [{:keys [id source executor sink] :as _workload}]
            (-> (update-source source)
                (update-executor! executor)
                (update-sink! sink))
            (update-workload-status)
            (workloads/load-workload-for-id tx id))]
    (if (and started (not finished)) (update! workload) workload)))

(def tdr-source-type "TerraDataRepoSink")

;; Note I've used a table to implement the queue and I'm deleting the row
;; when it's popped.
;;
;; You could just mark it as :visited or something.
(defn ^:private peek-tdr-source-queue [{:keys [queue] :as _source}]
  (let [query "SELECT * FROM %s ORDER BY id ASC LIMIT 1"]
    (jdbc/with-db-transaction [tx (postgres/wfl-db-config)]
      (first (jdbc/query tx (format query queue))))))

(defn ^:private pop-tdr-source-queue [{:keys [queue] :as source}]
  (if-let [{:keys [id] :as _snapshot} (peek-queue source)]
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

(defn ^:private load-tdr-source! [{:keys [source-ptr] :as workload}]
  {:type    tdr-source-type
   :queue   nil
   :updated nil})

(defoverload peek-queue    tdr-source-type peek-tdr-source-queue)
(defoverload pop-queue     tdr-source-type pop-tdr-source-queue)
(defoverload update-source tdr-source-type update-tdr-source)
(defoverload load-source!  tdr-source-type load-tdr-source!)

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
            [wfl.wfl :as wfl])
  (:import [java.time OffsetDateTime]))

(def pipeline "Sarscov2IlluminaFull")

;; TODO: implement COVID workload creation
;;  - make sure permissions/inputs are right upfront
;;  - dispatch on source/sink/executor
;;  - store information into database
(defn create-covid-workload!
  [tx {:keys [source sink executor] :as request}])

(defn start-covid-workload!
  [])

(defn ^:private snapshot-imported?
  "Check if snapshot SNAPSHOT_ID in TDR has been imported to WORKSPACE
  as SNAPSHOT_REFERENCE_ID and can be successfully fetched."
  [{:keys [snapshot_id snapshot_reference_id] :as _source_details}
   {:keys [workspace] :as _executor}]
  (letfn [(fetch [] (rawls/get-snapshot-reference workspace
                                                  snapshot_reference_id))]
    (some? (and snapshot_id snapshot_reference_id (util/do-or-nil (fetch))))))

(defn ^:private import-snapshot!
  "Import snapshot SNAPSHOT_ID from TDR to WORKSPACE.
  Use transaction TX to update DETAILS table with resulting reference id.
  Throw if import fails."
  [tx
   {:keys [workload_id] :as _workload}
   {:keys [details] :as _source}
   {:keys [details_id snapshot_id] :as source_details}
   {:keys [workspace] :as _executor}]
  (letfn [(create! [] (rawls/create-snapshot-reference workspace snapshot_id))]
    (if-let [reference-id (util/do-or-nil (:referenceId (create!)))]
      ((jdbc/update! tx
                     details
                     {:snapshot_reference_id reference-id}
                     ["id = ?" details_id])
       (jdbc/update! tx
                     :workload
                     {:updated (OffsetDateTime/now)}
                     ["id = ?" workload_id]))
      (throw (ex-info "Rawls unable to create snapshot reference"
                      (assoc source_details :workspace workspace))))))

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

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
            [wfl.wfl :as wfl]))

(def pipeline "Sarscov2IlluminaFull")

(defn ^:private add-workload-table!
  "Use transaction `tx` to add a `CromwellWorkflow` table
  for a `workload-request` running `workflow-wdl`."
  [tx workload-request]
  (let [{:keys [pipeline]} workload-request
        create "CREATE TABLE %s OF CromwellWorkflow (PRIMARY KEY (id))"
        setter "UPDATE workload SET pipeline = ?::pipeline WHERE id = ?"
        [{:keys [id]}]
        (-> workload-request
          ;; FIXME: update the DB schema to write these new information in
            (select-keys [:creator :executor :source :sink :project :labels :watchers])

            (merge (select-keys (wfl/get-the-version) [:commit :version]))
          ;; FIXME: how to write :release and :wdl
            (assoc :release release :wdl path :uuid (UUID/randomUUID))
            (->> (jdbc/insert! tx :workload)))
        table (format "%s_%09d" pipeline id)]
    (jdbc/execute!       tx [setter pipeline id])
    (jdbc/db-do-commands tx [(format create table)])
    (jdbc/update!        tx :workload {:items table} ["id = ?" id])
    [id table]))

;; TODO: implement COVID workload creation
(defn create-covid-workload!
  [tx {:keys [source sink executor] :as request}]
  ;; TODO: validation
  ;; TODO: dispatch on source/sink/executor
  (-> request
      (add-workload-table! tx)
      (workloads/load-workload-for-id tx id)))

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

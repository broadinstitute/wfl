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

(defn ^:private snapshot-imported?
  "Check if TDR snapshot has been imported to Terra Workspace.

  TODOs / QUESTIONS:
  - Do we need additional / altered arguments?
  - Should we have access to loaded workload from outer scope (I think so),
    or should every check in the update loop load the workload anew?
  - Do we check workload for presence of snapshot ID? How?
    (I think it could be related to items per aou. items = table name?)
  - Could one workload be associated with many snapshots?  Are we checking
    for all if so, or only for a specified snapshot?
  "
  [tx {:keys [id] :as workload}]
  (let [loaded (workloads/load-workload-for-id tx id)]
    (:snapshot_reference_id loaded)))

(defn ^:private import-snapshot!
  "Import TDR snapshot to Terra Workspace.

  TODOs / QUESTIONS:
  - Do we need additional / altered arguments?
  - How should we name the snapshot reference?
  - Where do we get workspace and snapshot ID? From workload?
    From an element within the workload?
  - How do we store new reference ID in our transaction recording?
    Associated with the workload, or something else?
  - Is this a JDBC update or insert?
  - Error handling?
  "
  [tx {:keys [id] :as workload}]
  (let [name (util/randomize "placeholder-snapshot-ref-name")
        workspace "workspace from workload?"
        snapshot-id (:snapshot_id workload) ;; Is this correct, or need to go deeper?
        reference-id (rawls/create-snapshot-reference workspace snapshot-id name)]
    (jdbc/update! tx :workload {:snapshot_reference_id reference-id} ["id = ?" id])))

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
            ;; Do we wish to save this loaded workload for our
            ;; to-be-defined workload update loop?
            ;; All of the checkers want to look at a loaded
            ;; workflow, and the update loop conditional makes it
            ;; such that we only do one task per round.
            (workloads/load-workload-for-id tx id))]
    (if (and started (not finished)) (update! workload) workload)))

(defoverload workloads/create-workload!   pipeline create-covid-workload!)
(defoverload workloads/start-workload!    pipeline start-covid-workload!)
(defoverload workloads/update-workload!   pipeline update-covid-workload!)
(defoverload workloads/stop-workload!     pipeline batch/stop-workload!)
(defoverload workloads/load-workload-impl pipeline
  batch/load-batch-workload-impl)

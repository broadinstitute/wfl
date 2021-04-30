(ns wfl.system.cdc-covid19-surveillance-demo
  (:require [clojure.test          :refer :all]
            [wfl.service.datarepo  :as datarepo]
            [wfl.service.firecloud :as firecloud]
            [wfl.service.rawls     :as rawls]
            [wfl.tools.fixtures    :as fixtures]
            [wfl.tools.snapshots   :as snapshots]
            [wfl.util              :as util]))

(def workspace-to-clone     "cdc-covid-surveillance/CDC_Viral_Sequencing_GP")
(def firecloud-group        "cdc-covid-surveillance")
(def snapshot-readers       ["cdc-covid-surveillance@firecloud.org"])
(def source-dataset         "cd25d59e-1451-44d0-8a24-7669edb9a8f8")
(def source-table           "flowcells")
(def snapshot-column        "run_date")
(def source-dataset-profile "395f5921-d2d9-480d-b302-f856d787c9d9")
(def method-configuration   "cdc-covid-surveillance/sarscov2_illumina_full")

(defn wait-for-user []
  (println "Press Enter to continue...")
  (read-line))

(defn clone-workspace []
  (println "Cloning workspace" workspace-to-clone)
  (let [clone-name (util/randomize "wfl-dev/CDC_Viral_Sequencing_GP")]
    (firecloud/clone-workspace workspace-to-clone clone-name firecloud-group)
    (println "Cloned new workspace " clone-name)
    clone-name))

(defn look-for-new-dataset-rows [{:keys [name] :as dataset}]
  (println "Looking for rows to snapshot in dataset" name)
  (let [row-ids (-> (datarepo/query-table-between
                     dataset source-table
                     snapshot-column
                     ["2021-04-24 03:00:00" "2021-04-26 05:00:00"]
                     [:datarepo_row_id])
                    :rows
                    flatten)]
    (println "Found rows" row-ids)
    row-ids))

(defn snapshot-new-dataset-rows [{:keys [name] :as dataset} row-ids]
  (println "Snapshotting rows" row-ids "in dataset" name)
  (let [{:keys [name] :as snapshot}
        (-> (snapshots/unique-snapshot-request
             source-dataset-profile
             dataset
             source-table
             row-ids)
            (assoc :readers snapshot-readers-group)
            datarepo/create-snapshot
            datarepo/snapshot)]
    (println "Created snapshot" name)
    snapshot))

(defn create-snapshot []
  (let [dataset (datarepo/dataset source-dataset)]
    (snapshot-new-dataset-rows dataset (look-for-new-dataset-rows dataset))))

(defn import-snapshot-into-workspace [workspace {:keys [name id] :as _snapshot}]
  (println "Importing snapshot" name "into" workspace)
  (let [{:keys [name] :as ref} (rawls/create-snapshot-reference workspace id name)]
    (println "Created snapshot reference" name)
    ref))

(defn update-method-configuration
  [workspace {:keys [name] :as _snapshot-reference}]
  (println "Updating" method-configuration "to use" name)
  (-> (firecloud/get-method-configuration workspace method-configuration)
      (assoc :dataReferenceName name)
      (->> (firecloud/update-method-configuration workspace method-configuration))))

(defn submit-snapshot-reference
  [workspace {:keys [name] :as _snapshot-reference}]
  (println "Submitting" name "in" workspace)
  (let [submission (firecloud/submit-method workspace method-configuration)
        [id count] ((juxt :submissionId (comp count :workflows)) submission)]
    (println "Created submission" id "-" count "workflow(s)")
    submission))

(defn abort-submission [workspace {:keys [submissionId] :as _submission}]
  (println "Aborting" submissionId "in" workspace)
  (firecloud/abort-submission workspace submissionId))

(defn write-known-outputs-to-workspace [workspace]
  (println "Writing outputs to flowcell table in" workspace))

(defn delete-snapshot [{:keys [name id] :as _snapshot}]
  (println "Deleting snapshot" name)
  (datarepo/delete-snapshot id))

(defn delete-workspace [workspace]
  (println "Deleting" workspace)
  (firecloud/delete-workspace workspace))

(defn demo []
  (println "CDC COVID-19 Surveillance Demo")
  (println "Demonstrating sarscov2_illumina_full automation")
  (wait-for-user)
  (fixtures/with-fixtures
    [(util/bracket clone-workspace delete-workspace)
     (util/bracket #(wait-for-user) (constantly nil))
     (util/bracket create-snapshot delete-snapshot)]
    (fn [[workspace _ snapshot]]
      (wait-for-user)
      (let [reference  (import-snapshot-into-workspace workspace snapshot)
            _          (wait-for-user)
            _          (update-method-configuration workspace reference)
            submission (submit-snapshot-reference workspace reference)]
        (wait-for-user)
        (abort-submission workspace submission))
      (write-known-outputs-to-workspace workspace)
      (wait-for-user)
      (println "Cleaning up"))))

(def prod-env
  {"WFL_TDR_URL"   "https://data.terra.bio"
   "WFL_RAWLS_URL" "https://rawls.dsde-prod.broadinstitute.org"})

(defn -main []
  (try
    (fixtures/with-temporary-environment prod-env demo)
    (catch Throwable t
      (binding [*out* *err*]
        (println t)
        (System/exit 1)))))
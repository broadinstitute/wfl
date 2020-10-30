(ns wfl.tools.workloads
  (:require [clojure.tools.logging.readable :as log]
            [wfl.environments :refer [stuff]]
            [wfl.module.aou :as aou]
            [wfl.module.copyfile :as cp]
            [wfl.module.wgs :as wgs]
            [wfl.module.xx :as xx]
            [wfl.service.postgres :as postgres]
            [wfl.service.cromwell :as cromwell]
            [wfl.tools.endpoints :as endpoints]
            [wfl.util :refer [shell!]]
            [wfl.util :as util]
            [wfl.jdbc :as jdbc]
            [wfl.tools.fixtures :as fixtures])
  (:import (java.util.concurrent TimeoutException)))

(def git-branch (delay (util/shell! "git" "branch" "--show-current")))

(def wgs-inputs
  {:unmapped_bam_suffix  ".unmapped.bam",
   :sample_name          "NA12878 PLUMBING",
   :base_file_name       "NA12878_PLUMBING",
   :final_gvcf_base_name "NA12878_PLUMBING",
   :input_cram           "develop/20k/NA12878_PLUMBING.cram"})

(defn wgs-workload-request
  [identifier]
  "A whole genome sequencing workload used for testing."
  (let [path "/single_sample/plumbing/truth"]
    {:cromwell (get-in stuff [:wgs-dev :cromwell :url])
     :input    (str "gs://broad-gotc-dev-wfl-ptc-test-inputs" path)
     :output   (str "gs://broad-gotc-dev-wfl-ptc-test-outputs/wgs-test-output/" identifier)
     :pipeline wgs/pipeline
     :project  (format "(Test) %s" @git-branch)
     :items    [{:inputs wgs-inputs}]}))

(defn aou-workload-request
  "An allofus arrays workload used for testing.
  Randomize it with IDENTIFIER for easier testing."
  [identifier]
  {:cromwell (get-in stuff [:aou-dev :cromwell :url])
   :output   "gs://broad-gotc-dev-wfl-ptc-test-outputs/aou-test-output"
   :pipeline aou/pipeline
   :project  (format "(Test) %s %s" @git-branch identifier)})

(def aou-sample
  "An aou arrays sample for testing."
  {:chip_well_barcode           "7991775143_R01C01",
   :bead_pool_manifest_file     "gs://broad-gotc-dev-wfl-ptc-test-inputs/arrays/metadata/HumanExome-12v1-1_A/HumanExome-12v1-1_A.bpm",
   :analysis_version_number     1,
   :call_rate_threshold         0.98
   :extended_chip_manifest_file "gs://broad-gotc-dev-wfl-ptc-test-inputs/arrays/metadata/HumanExome-12v1-1_A/HumanExome-12v1-1_A.1.3.extended.csv",
   :red_idat_cloud_path         "gs://broad-gotc-dev-wfl-ptc-test-inputs/arrays/HumanExome-12v1-1_A/idats/7991775143_R01C01/7991775143_R01C01_Red.idat",
   :zcall_thresholds_file       "gs://broad-gotc-dev-wfl-ptc-test-inputs/arrays/metadata/HumanExome-12v1-1_A/IBDPRISM_EX.egt.thresholds.txt",
   :reported_gender             "Female",
   :cluster_file                "gs://broad-gotc-dev-wfl-ptc-test-inputs/arrays/metadata/HumanExome-12v1-1_A/HumanExomev1_1_CEPH_A.egt",
   :sample_lsid                 "broadinstitute.org:bsp.dev.sample:NOTREAL.NA12878",
   :sample_alias                "NA12878",
   :green_idat_cloud_path       "gs://broad-gotc-dev-wfl-ptc-test-inputs/arrays/HumanExome-12v1-1_A/idats/7991775143_R01C01/7991775143_R01C01_Grn.idat",
   :params_file                 "gs://broad-gotc-dev-wfl-ptc-test-inputs/arrays/HumanExome-12v1-1_A/inputs/7991775143_R01C01/params.txt",
   :gender_cluster_file         "gs://broad-gotc-dev-wfl-ptc-test-inputs/arrays/metadata/HumanExome-12v1-1_A/HumanExomev1_1_gender.egt"})

(defn copyfile-workload-request
  "Make a workload to copy a file from SRC to DST"
  [src dst]
  {:cromwell (get-in stuff [:gotc-dev :cromwell :url])
   :output   ""
   :pipeline cp/pipeline
   :project  (format "(Test) %s" @git-branch)
   :items    [{:inputs {:src src :dst dst}}]})

(defn xx-workload-request
  [identifier]
  "A whole genome sequencing workload used for testing."
  (let [test-storage "gs://broad-gotc-dev-wfl-ptc-test-inputs/single_sample/plumbing/truth/develop/20k/"]
    {:cromwell      (get-in stuff [:xx-dev :cromwell :url])
     :output        (str "gs://broad-gotc-dev-wfl-ptc-test-outputs/xx-test-output/" identifier)
     :pipeline      xx/pipeline
     :project       (format "(Test) %s" @git-branch)
     :common_inputs {:ExomeReprocessing.ExomeGermlineSingleSample.UnmappedBamToAlignedBam.CheckContamination.disable_sanity_check true}
     :items         [{:inputs {:input_cram (str test-storage "NA12878_PLUMBING.cram")}}]}))

(defn when-done
  "Call `done!` when cromwell has finished executing `workload`'s workflows."
  [done! {:keys [cromwell] :as workload}]
  (letfn [(await-workflow [{:keys [uuid] :as workflow}]
            (let [interval  10
                  timeout   3600                            ; 1 hour
                  finished? (set cromwell/final-statuses)
                  skipped?  #(-> % :uuid util/uuid-nil?)]   ; see wgs. i die.
              (loop [seconds 0]
                (when (> seconds timeout)
                  (throw (TimeoutException.
                           (format "Timed out waiting for workflow %s" uuid))))
                (when-not (skipped? workflow)
                  (let [status (postgres/cromwell-status cromwell uuid)]
                    (when-not (finished? status)
                      (log/infof "%s: Sleeping on status: %s" uuid status)
                      (util/sleep-seconds interval)
                      (recur (+ seconds interval))))))))]
    (run! await-workflow (:workflows workload))
    (done! (endpoints/get-workload-status (:uuid workload)))
    nil))

(defn baseline-options-across-workload
  "True if OPTIONS are present across the entire WORKLOAD."
  [options workload]
  (let [unique-options (distinct (conj (map :workflow_options (:workflows workload))
                                       (:workflow_options workload)))]
    (every? #(= % (util/deep-merge % options)) unique-options)))

(defn create-workload! [workload-request]
  (jdbc/with-db-transaction [tx (fixtures/testing-db-config)]
    (wfl.api.workloads/create-workload! tx workload-request)))

(defn start-workload! [workload]
  (jdbc/with-db-transaction [tx (fixtures/testing-db-config)]
    (wfl.api.workloads/start-workload! tx workload)))

(defn execute-workload! [workload-request]
  (jdbc/with-db-transaction [tx (fixtures/testing-db-config)]
    (wfl.api.workloads/execute-workload! tx workload-request)))

(defn update-workload! [workload]
  (jdbc/with-db-transaction [tx (fixtures/testing-db-config)]
    (wfl.api.workloads/update-workload! tx workload)))

(defn load-workload-for-uuid [uuid]
  (jdbc/with-db-transaction [tx (fixtures/testing-db-config)]
    (wfl.api.workloads/load-workload-for-uuid tx uuid)))

(defn load-workload-for-id [id]
  (jdbc/with-db-transaction [tx (fixtures/testing-db-config)]
    (wfl.api.workloads/load-workload-for-id tx id)))

(defn append-to-workload! [samples workload]
  (jdbc/with-db-transaction [tx (fixtures/testing-db-config)]
    (aou/append-to-workload! tx samples workload)))

(ns wfl.tools.workloads
  (:require [clojure.string :as str]
            [clojure.tools.logging.readable :as log]
            [wfl.environments :refer [stuff]]
            [wfl.jdbc :as jdbc]
            [wfl.module.aou :as aou]
            [wfl.module.copyfile :as cp]
            [wfl.module.wgs :as wgs]
            [wfl.module.xx :as xx]
            [wfl.service.postgres :as postgres]
            [wfl.service.cromwell :as cromwell]
            [wfl.tools.endpoints :as endpoints]
            [wfl.tools.fixtures :as fixtures]
            [wfl.util :as util :refer [shell!]])
  (:import (java.util.concurrent TimeoutException)))

(def git-branch (delay (util/shell! "git" "branch" "--show-current")))

(def wgs-inputs
  (let [input-folder
        (str/join "/" ["gs://broad-gotc-dev-wfl-ptc-test-inputs"
                       "single_sample/plumbing/truth/develop/20k/"])]
    {:unmapped_bam_suffix  ".unmapped.bam",
     :sample_name          "NA12878 PLUMBING",
     :base_file_name       "NA12878_PLUMBING",
     :final_gvcf_base_name "NA12878_PLUMBING",
     :input_cram           (str input-folder "NA12878_PLUMBING.cram")}))

(defn wgs-workload-request
  [identifier]
  "A whole genome sequencing workload used for testing."
  {:cromwell (get-in stuff [:wgs-dev :cromwell :url])
   :output   (str "gs://broad-gotc-dev-wfl-ptc-test-outputs/wgs-test-output/" identifier)
   :pipeline wgs/pipeline
   :project  (format "(Test) %s" @git-branch)
   :items    [{:inputs wgs-inputs}]
   :common   {:inputs (-> {:disable_sanity_check true}
                        (util/prefix-keys :CheckContamination)
                        (util/prefix-keys :UnmappedBamToAlignedBam)
                        (util/prefix-keys :WholeGenomeGermlineSingleSample)
                        (util/prefix-keys :WholeGenomeReprocessing))}})

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

(def xx-inputs
  (let [storage "gs://broad-gotc-dev-wfl-ptc-test-inputs/single_sample/plumbing/truth/develop/20k/"]
    {:input_cram (str storage "NA12878_PLUMBING.cram")}))

(defn xx-workload-request
  [identifier]
  "A whole genome sequencing workload used for testing."
  {:cromwell (get-in stuff [:xx-dev :cromwell :url])
   :output   (str "gs://broad-gotc-dev-wfl-ptc-test-outputs/xx-test-output/" identifier)
   :pipeline xx/pipeline
   :project  (format "(Test) %s" @git-branch)
   :items    [{:inputs xx-inputs}]
   :common {:inputs (-> {:disable_sanity_check true}
                      (util/prefix-keys :CheckContamination)
                      (util/prefix-keys :UnmappedBamToAlignedBam)
                      (util/prefix-keys :ExomeGermlineSingleSample)
                      (util/prefix-keys :ExomeReprocessing))}})

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

;; `doall` is required here for testing otherwise the moment test uses
;; the result and tries to force realizing, the db tx is already closed
;;
(defn load-workloads-with-project [project]
  (jdbc/with-db-transaction [tx (fixtures/testing-db-config)]
    (doall
      (wfl.api.workloads/load-workloads-with-project tx project))))

(defn append-to-workload! [samples workload]
  (jdbc/with-db-transaction [tx (fixtures/testing-db-config)]
    (aou/append-to-workload! tx samples workload)))

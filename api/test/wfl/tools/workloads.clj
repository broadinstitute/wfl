(ns wfl.tools.workloads
  (:require [clojure.string :as str]
            [clojure.tools.logging.readable :as log]
            [wfl.environments :refer [stuff]]
            [wfl.jdbc :as jdbc]
            [wfl.module.aou :as aou]
            [wfl.module.arrays :as arrays]
            [wfl.module.copyfile :as cp]
            [wfl.module.wgs :as wgs]
            [wfl.module.xx :as xx]
            [wfl.service.cromwell :as cromwell]
            [wfl.tools.endpoints :as endpoints]
            [wfl.tools.fixtures :as fixtures]
            [wfl.util :as util :refer [shell!]]
            [wfl.module.all :as all]
            [wfl.module.sg :as sg])
  (:import (java.util.concurrent TimeoutException)))

(def git-branch (delay (util/shell! "git" "branch" "--show-current")))

(defn ^:private load-cromwell-url-from-env-var!
  "Load Cromwell url from the env var CROMWELL."
  []
  (some-> "CROMWELL"
          util/getenv
          all/de-slashify))

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
  {:executor (or (load-cromwell-url-from-env-var!) (get-in stuff [:wgs-dev :cromwell :url]))
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
  "An AllOfUs arrays workload used for testing.
  Randomize it with IDENTIFIER for easier testing."
  [identifier]
  {:executor (or (load-cromwell-url-from-env-var!) (get-in stuff [:aou-dev :cromwell :url]))
   :output   "gs://broad-gotc-dev-wfl-ptc-test-outputs/aou-test-output/"
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

(def arrays-sample
  {:sample_alias                    "03C 17319",
   :sample_lsid                     "broadinstitute.org:bsp.dev.sample:NOTREAL.03C17319",
   :analysis_version_number         1,
   :call_rate_threshold             0.98,
   :genotype_concordance_threshold  0.98,
   :reported_gender                 "Male",
   :chip_well_barcode               "200598830050_R07C01",
   :green_idat_cloud_path           "gs://broad-gotc-dev-wfl-ptc-test-inputs/arrays/PsychChip_v1-1_15073391_A1/idats/200598830050_R07C01/200598830050_R07C01_Grn.idat",
   :red_idat_cloud_path             "gs://broad-gotc-dev-wfl-ptc-test-inputs/arrays/PsychChip_v1-1_15073391_A1/idats/200598830050_R07C01/200598830050_R07C01_Red.idat",
   :params_file                     "gs://broad-gotc-dev-wfl-ptc-test-inputs/arrays/PsychChip_v1-1_15073391_A1/inputs/200598830050_R07C01/params.txt",
   :fingerprint_genotypes_vcf_file  "gs://broad-gotc-dev-wfl-ptc-test-inputs/arrays/PsychChip_v1-1_15073391_A1/inputs/200598830050_R07C01/200598830050_R07C01.03C_17319.reference.fingerprint.vcf.gz",
   :fingerprint_genotypes_vcf_index_file "gs://broad-gotc-dev-wfl-ptc-test-inputs/arrays/PsychChip_v1-1_15073391_A1/inputs/200598830050_R07C01/200598830050_R07C01.03C_17319.reference.fingerprint.vcf.gz.tbi",
   :bead_pool_manifest_file         "gs://broad-gotc-dev-wfl-ptc-test-inputs/arrays/metadata/PsychChip_v1-1_15073391_A1/PsychChip_v1-1_15073391_A1.bpm",
   :extended_chip_manifest_file     "gs://broad-gotc-dev-wfl-ptc-test-inputs/arrays/metadata/PsychChip_v1-1_15073391_A1/PsychChip_v1-1_15073391_A1.1.3.extended.csv",
   :cluster_file                    "gs://broad-gotc-dev-wfl-ptc-test-inputs/arrays/metadata/PsychChip_v1-1_15073391_A1/PsychChip_v1-1_15073391_A1_ClusterFile.egt",
   :zcall_thresholds_file           "gs://broad-gotc-dev-wfl-ptc-test-inputs/arrays/metadata/PsychChip_v1-1_15073391_A1/thresholds.7.txt"})

(def arrays-sample-terra
  {:entity-name "200598830050_R07C01-1"
   :entity-type "sample"})

(defn arrays-workload-request
  [identifier]
  {:executor (or (load-cromwell-url-from-env-var!) "https://firecloud-orchestration.dsde-dev.broadinstitute.org")
   :output   (str "gs://broad-gotc-dev-wfl-ptc-test-outputs/arrays-test-output/" identifier)
   :pipeline arrays/pipeline
   :project  "general-dev-billing-account/arrays"
   :items   [{:inputs arrays-sample-terra}]})

(defn copyfile-workload-request
  "Make a workload to copy a file from SRC to DST"
  [src dst]
  {:executor (or (load-cromwell-url-from-env-var!) (get-in stuff [:gotc-dev :cromwell :url]))
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
  {:executor (or (load-cromwell-url-from-env-var!) (get-in stuff [:xx-dev :cromwell :url]))
   :output   (str "gs://broad-gotc-dev-wfl-ptc-test-outputs/xx-test-output/" identifier)
   :pipeline xx/pipeline
   :project  (format "(Test) %s" @git-branch)
   :items    [{:inputs xx-inputs}]
   :common {:inputs (-> {:disable_sanity_check true}
                        (util/prefix-keys :CheckContamination)
                        (util/prefix-keys :UnmappedBamToAlignedBam)
                        (util/prefix-keys :ExomeGermlineSingleSample)
                        (util/prefix-keys :ExomeReprocessing))}})

(def sg-inputs
  (let [storage "gs://broad-gotc-dev-wfl-ptc-test-inputs/single_sample/plumbing/truth/develop/20k/"]
    {:ubam (str storage "NA12878_PLUMBING.unmapped.bam")}))

(defn sg-workload-request
  [identifier]
  {:executor (or (load-cromwell-url-from-env-var!) (get-in stuff [:wgs-dev :cromwell :url]))
   :output   (str "gs://broad-gotc-dev-wfl-ptc-test-outputs/sg-test-output/" identifier)
   :pipeline sg/pipeline
   :project  (format "(Test) %s" @git-branch)
   :items    [{:inputs sg-inputs}]})

(defn when-done
  "Call `done!` when all workflows in the `workload` have finished processing."
  [done! {:keys [uuid] :as workload}]
  (letfn [(finished? [{:keys [status] :as workflow}]
            (let [skipped? #(-> % :uuid util/uuid-nil?)]
              (or (skipped? workflow) ((set cromwell/final-statuses) status))))]
    (let [interval 10
          timeout  3600]                                    ; 1 hour
      (loop [elapsed 0 wl workload]
        (when (> elapsed timeout)
          (throw (TimeoutException.
                  (format "Timed out waiting for workload %s" uuid))))
        (if (every? finished? (:workflows wl))
          (done! wl)
          (do
            (log/infof "Waiting for workload %s to complete" uuid)
            (util/sleep-seconds interval)
            (recur (+ elapsed interval)
                   (endpoints/get-workload-status uuid))))))))

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

(defn load-workloads-with-project [project]
  (jdbc/with-db-transaction [tx (fixtures/testing-db-config)]
    (wfl.api.workloads/load-workloads-with-project tx project)))

(defn append-to-workload! [samples workload]
  (jdbc/with-db-transaction [tx (fixtures/testing-db-config)]
    (aou/append-to-workload! tx samples workload)))

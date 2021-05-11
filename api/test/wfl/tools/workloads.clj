(ns wfl.tools.workloads
  (:require [clojure.string :as str]
            [clojure.tools.logging.readable :as log]
            [wfl.auth :as auth]
            [wfl.environment :as env]
            [wfl.jdbc :as jdbc]
            [wfl.module.aou :as aou]
            [wfl.module.arrays :as arrays]
            [wfl.module.copyfile :as cp]
            [wfl.module.covid :as covid]
            [wfl.module.sg :as sg]
            [wfl.module.wgs :as wgs]
            [wfl.module.xx :as xx]
            [wfl.service.clio :as clio]
            [wfl.service.cromwell :as cromwell]
            [wfl.service.google.storage :as gcs]
            [wfl.tools.endpoints :as endpoints]
            [wfl.tools.fixtures :as fixtures]
            [wfl.util :as util :refer [shell!]])
  (:import (java.time OffsetDateTime)
           (java.util.concurrent TimeoutException)
           (java.util UUID)))

(def clio-url (delay (env/getenv "WFL_CLIO_URL")))

(def email
  (delay (:email (gcs/userinfo {:headers (auth/get-auth-header)}))))

(def ^:private git-branch
  (delay (util/shell! "git" "branch" "--show-current")))

(def project
  (delay (format "(Test) %s" (str @git-branch))))

(def cromwell-url (delay (env/getenv "WFL_CROMWELL_URL")))

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
  {:executor @cromwell-url
   :output   (str "gs://broad-gotc-dev-wfl-ptc-test-outputs/wgs-test-output/"
                  identifier)
   :pipeline wgs/pipeline
   :project  @project
   :items    [{:inputs wgs-inputs}]
   :common   {:inputs (-> {:disable_sanity_check true}
                          (util/prefix-keys :CheckContamination.)
                          (util/prefix-keys :UnmappedBamToAlignedBam.)
                          (util/prefix-keys :WholeGenomeGermlineSingleSample.)
                          (util/prefix-keys :WholeGenomeReprocessing.))}})

(defn aou-workload-request
  "An AllOfUs arrays workload used for testing.
  Randomize it with IDENTIFIER for easier testing."
  [identifier]
  {:executor @cromwell-url
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
  {:executor (env/getenv "WFL_FIRECLOUD_URL")
   :output   (str "gs://broad-gotc-dev-wfl-ptc-test-outputs/arrays-test-output/"
                  identifier)
   :pipeline arrays/pipeline
   :project  "general-dev-billing-account/arrays"
   :items   [{:inputs arrays-sample-terra}]})

(defn copyfile-workload-request
  "Make a workload to copy a file from SRC to DST"
  [src dst]
  {:executor @cromwell-url
   :output   ""
   :pipeline cp/pipeline
   :project  @project
   :items    [{:inputs {:src src :dst dst}}]})

(defn covid-workload-request
  "Make a COVID Sarscov2IlluminaFull workload creation request."
  [source executor sink]
  {:source   (merge
              {:name    "Terra DataRepo",
               :dataset ""
               :table   ""
               :column  ""}
              source)
   :executor (merge
              {:name                       "Terra"
               :workspace                  "namespace/name"
               :methodConfiguration        ""
               :methodConfigurationVersion 0
               :fromSource                 ""}
              executor)
   :sink     (merge
              {:name        "Terra Workspace"
               :workspace   "namespace/name"
               :entity      ""
               :fromOutputs {}}
              sink)
   :pipeline covid/pipeline
   :project  @project
   :creator  @email
   :labels   ["hornet:test"]})

(defn xx-workload-request
  [identifier]
  "A whole genome sequencing workload used for testing."
  {:executor @cromwell-url
   :output   (str/join "/" ["gs://broad-gotc-dev-wfl-ptc-test-outputs"
                            "xx-test-output" identifier])
   :pipeline xx/pipeline
   :project  @project
   :items    [{:inputs {:input_cram
                        (str "gs://broad-gotc-dev-wfl-ptc-test-inputs/"
                             "single_sample/plumbing/truth/develop/20k/"
                             "NA12878_PLUMBING.cram")}}]
   :common {:inputs (-> {:disable_sanity_check true}
                        (util/prefix-keys :CheckContamination.)
                        (util/prefix-keys :UnmappedBamToAlignedBam.)
                        (util/prefix-keys :ExomeGermlineSingleSample.)
                        (util/prefix-keys :ExomeReprocessing.))}})

(defn ^:private add-clio-cram
  "Ensure there are files in GCS to satisfy the Clio `query`."
  [{:keys [project version] :as query}]
  (let [suffix {:crai_path                  ".cram.crai"
                :cram_path                  ".cram"
                :insert_size_histogram_path ".insert_size_histogram.pdf"
                :insert_size_metrics_path   ".insert_size_metrics"}
        prefix #(zipmap (keys suffix) (map (partial str %) (vals suffix)))
        froms  (prefix (str/join "/" ["gs://broad-gotc-test-storage"
                                      "germline_single_sample/wgs"
                                      "plumbing/truth/develop"
                                      "G96830.NA12878"
                                      "NA12878_PLUMBING"]))
        tos    (prefix (str/join "/" ["gs://broad-gotc-dev-wfl-sg-test-inputs"
                                      "pipeline" project "NA12878"
                                      (str \v version) "NA12878"]))]
    (clio/add-cram
     @clio-url
     (merge query tos
            {:cromwell_id         (str (UUID/randomUUID))
             :workflow_start_date (str (OffsetDateTime/now))}))
    (dorun (map (fn [k] (gcs/copy-object (k froms) (k tos))) (keys suffix))))
  (first (clio/query-cram @clio-url query)))

(defn ^:private ensure-clio-cram
  "Ensure there is a unique CRAM record in Clio suitable for test."
  [identifier]
  (let [version 23
        project (str "G96830" \- identifier)
        query   {:billing_project        "hornet-nest"
                 :cram_md5               "0cfd2e0890f45e5f836b7a82edb3776b"
                 :cram_size              19512619343
                 :data_type              "WGS"
                 :document_status        "Normal"
                 :location               "GCP"
                 :notes                  "Blame tbl for SG test."
                 :pipeline_version       "f1c7883"
                 :project                project
                 :readgroup_md5          "a128cbbe435e12a8959199a8bde5541c"
                 :regulatory_designation "RESEARCH_ONLY"
                 :sample_alias           "NA12878"
                 :version                version
                 :workspace_name         "bike-of-hornets"}
        crams   (clio/query-cram @clio-url query)]
    (when (> (count crams) 1)
      (throw (ex-info "More than 1 Clio CRAM record" {:crams crams})))
    (or (first crams)
        (add-clio-cram query))))

;; From warp.git ExampleCramToUnmappedBams.plumbing.json
;;
(defn sg-workload-request
  [identifier]
  (let [{:keys [cram_path sample_alias]} (ensure-clio-cram identifier)
        dbsnp (str/join "/" ["gs://broad-gotc-dev-storage/temp_references"
                             "gdc/dbsnp_144.hg38.vcf.gz"])
        fasta (str/join "/" ["gs://gcp-public-data--broad-references/hg38/v0"
                             "Homo_sapiens_assembly38.fasta"])
        vcf   (str/join "/" ["gs://gatk-best-practices/somatic-hg38"
                             "small_exac_common_3.hg38.vcf.gz"])]
    {:executor @cromwell-url
     :output   (str/join "/" ["gs://broad-gotc-dev-wfl-sg-test-outputs"
                              identifier])
     :pipeline sg/pipeline
     :project  @project
     :items    [{:inputs
                 {:base_file_name          sample_alias
                  :contamination_vcf       vcf
                  :contamination_vcf_index (str vcf ".tbi")
                  :cram_ref_fasta          fasta
                  :cram_ref_fasta_index    (str fasta ".fai")
                  :dbsnp_vcf               dbsnp
                  :dbsnp_vcf_index         (str dbsnp ".tbi")
                  :input_cram              cram_path}}]}))

(defn when-done
  "Call `done!` when all workflows in the `workload` have finished processing."
  [done! {:keys [uuid] :as workload}]
  (letfn [(finished? [{:keys [status] :as workflow}]
            (let [skipped? #(-> % :uuid util/uuid-nil?)]
              (or (skipped? workflow) ((set cromwell/final-statuses) status))))]
    (let [interval 10
          timeout  4800]                ; 80 minutes
      (loop [elapsed 0 wl workload]
        (when (> elapsed timeout)
          (throw (TimeoutException.
                  (format "Timed out waiting for workload %s" uuid))))
        (if (or (:finished workload) (every? finished? (:workflows wl)))
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

(defn stop-workload! [workload]
  (jdbc/with-db-transaction [tx (fixtures/testing-db-config)]
    (wfl.api.workloads/stop-workload! tx workload)))

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

(defmulti  postcheck
  "Implement this to validate `workload` after all workflows complete."
  (fn [workload] (:pipeline workload)))

(defmethod postcheck :default [_])

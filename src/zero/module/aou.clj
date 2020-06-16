(ns zero.module.aou
  "Process Arrays for the All Of Us project."
  (:require [clojure.java.io :as io]
            [clojure.data.json :as json]
            [clojure.java.jdbc :as jdbc]
            [clojure.set :as set]
            [clojure.string :as str]
            [zero.environments :as env]
            [zero.module.all :as all]
            [zero.references :as references]
            [zero.service.cromwell :as cromwell]
            [zero.service.postgres :as postgres]
            [zero.service.gcs :as gcs]
            [zero.util :as util]
            [zero.wdl :as wdl]
            [zero.zero :as zero]))

(def description
  "Describe the purpose of this command."
  (let [in  (str "")
        out "gs://broad-gotc-dev-zero-test/aou"
        title (str (str/capitalize zero/the-name) ":")]
    (-> [""
         "%2$s Process Illumina Genotyping Array data"
         "%2$s %1$s aou process array data."
         ""
         "Usage: %1$s aou <env> <in> <out>"
         "       %1$s aou <env> <max> <in> <out>"
         ""
         "Where: <env> is an environment,"
         "       <max> is a non-negative integer,"
         "       and <in> and <out> are GCS urls."
         "       <max> is the maximum number of inputs to process."
         "       <in>  is a GCS url to files ending in '.bam' or '.cram'."
         "       <out> is an output URL for the reprocessed CRAMs."
         ""
         "The 3-argument command reports on the status of workflows"
         "and the counts of BAMs and CRAMs in the <in> and <out> urls."
         ""
         "The 4-argument command submits up to <max> .bam or .cram files"
         "from the <in> URL to the Cromwell in environment <env>."
         ""
         (str/join \space ["Example: %1$s wgs wgs-dev 42" in out])]
        (->> (str/join \newline))
        (format zero/the-name title))))

(def workflow-wdl
  "The top-level WDL file and its version."
  {:release "Arrays_v1.7"
   :top     "pipelines/arrays/single_sample/Arrays.wdl"})

(def cromwell-label-map
  "The WDL label applied to Cromwell metadata."
  {(keyword (str zero/the-name "-aou"))
   (wdl/workflow-name (:top workflow-wdl))})

(def cromwell-label
  "The WDL label applied to Cromwell metadata."
  (let [[key value] (first cromwell-label-map)]
    (str (name key) ":" value)))

(def per-sample
  "The sample specific inputs for arrays."
  {:chip_well_barcode       nil
   :sample_alias            nil
   :red_idat_cloud_path     nil
   :green_idat_cloud_path   nil
   :analysis_version_number nil
   :sample_lsid             nil
   :reported_gender         nil
   :params_file             nil})

(def chip-metadata
  "Chip Metadata inputs for arrays."
  {:bead_pool_manifest_file     nil
   :bead_pool_manifest_csv_file nil
   :extended_chip_manifest_file nil
   :cluster_file                nil
   :gender_cluster_file         nil
   :zcall_thresholds_file       nil}
  )

(def fingerprinting
  "Fingerprinting inputs for arrays."
  {:fingerprint_genotypes_vcf_file       nil
   :fingerprint_genotypes_vcf_index_file nil
   :haplotype_database_file              "gs://broad-references-private/hg19/v0/Homo_sapiens_assembly19.haplotype_database.txt"
   :variant_rsids_file                   "gs://broad-references-private/hg19/v0/Homo_sapiens_assembly19.haplotype_database.snps.list"}
  )

(def genotype-concordance
  "Genotype Concordance inputs for arrays."
  {:control_sample_vcf_file       nil
   :control_sample_vcf_index_file nil
   :control_sample_intervals_file nil
   :control_sample_name           nil})

(def other-inputs
  "Miscellaneous inputs for arrays."
  {:call_rate_threshold              nil
   :genotype_concordance_threshold   nil
   :contamination_controls_vcf       nil
   :subsampled_metrics_interval_list nil
   :disk_size                        nil
   :preemptible_tries                 3})

(defn array-inputs
  "Array inputs for ENVIRONMENT that do not depend on the input file."
  [environment]
  {:vault_token_path (get-in env/stuff [environment :vault_token_path])
   ; TODO: convert environment to "dev", "staging" or "prod"
   :environment      "dev"})

(defn make-inputs
  "Return inputs for processing IN-GS into OUT-GS in ENVIRONMENT."
  [environment out-gs in-gs]
  (merge references/hg19-arrays-references
         per-sample
         chip-metadata
         fingerprinting
         genotype-concordance
         other-inputs
         (array-inputs environment)))

(defn active-objects
  "GCS object names of array samples from IN-GS-URL now active in ENVIRONMENT."
  [environment in-gs-url]
  nil)

(defn really-submit-one-workflow
  "Submit IN-GS for processing into OUT-GS in ENVIRONMENT."
  [environment in-gs out-gs]
  (let [path (wdl/hack-unpack-resources-hack workflow-wdl)]
    (cromwell/submit-workflow
      environment
      (io/file (:dir path) (path ".wdl"))
      (io/file (:dir path) (path ".zip"))
      (make-inputs environment out-gs in-gs)
      (util/make-options environment)
      cromwell-label-map)))

(defn submit-workflow
  "Submit OBJECT from IN-BUCKET for processing into OUT-GS in
  ENVIRONMENT."
  [environment in-bucket out-gs object]
  (let [in-gs (gcs/gs-url in-bucket object)]
    (really-submit-one-workflow environment in-gs out-gs)))

(def pipeline "AllOfUsArrays")

(defn update-workload!
  "Use transaction TX to update WORKLOAD statuses."
  [tx workload])

(defn add-workload!
  "Add the workload described by BODY to the database DB."
  [tx body]
  (->> body
       first
       (filter second)
       (into {})))

(defn start-workload!
  "Start the WORKLOAD in the database DB."
  [tx workload]
  (->> workload
       (filter second)
       (into {})))

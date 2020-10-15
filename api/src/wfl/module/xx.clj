(ns wfl.module.xx
  "Reprocess External Exomes, whatever they are."
  (:require [clojure.data.json :as json]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.tools.logging.readable :as logr]
            [wfl.api.workloads :refer [defoverload]]
            [wfl.api.workloads :as workloads]
            [wfl.environments :as env]
            [wfl.jdbc :as jdbc]
            [wfl.module.all :as all]
            [wfl.service.gcs :as gcs]
            [wfl.service.postgres :as postgres]
            [wfl.service.cromwell :as cromwell]
            [wfl.util :as util]
            [wfl.wdl :as wdl]
            [wfl.wfl :as wfl])
  (:import [java.time OffsetDateTime]))

(def pipeline "ExternalExomeReprocessing")

(def cromwell-labels
  "The WDL label applied to Cromwell metadata."
  {(keyword wfl/the-name) pipeline})

(def workflow-wdl
  "The top-level WDL file and its version."
  {:release "ExternalExomeReprocessing_v2.1.1"
   :top     "pipelines/broad/reprocessing/external/exome/ExternalExomeReprocessing.wdl"})

(def reference-fasta-defaults
  {:ref_pac         "gs://gcp-public-data--broad-references/hg38/v0/Homo_sapiens_assembly38.fasta.64.pac"
   :ref_bwt         "gs://gcp-public-data--broad-references/hg38/v0/Homo_sapiens_assembly38.fasta.64.bwt"
   :ref_dict        "gs://gcp-public-data--broad-references/hg38/v0/Homo_sapiens_assembly38.dict"
   :ref_ann         "gs://gcp-public-data--broad-references/hg38/v0/Homo_sapiens_assembly38.fasta.64.ann"
   :ref_fasta_index "gs://gcp-public-data--broad-references/hg38/v0/Homo_sapiens_assembly38.fasta.fai"
   :ref_alt         "gs://gcp-public-data--broad-references/hg38/v0/Homo_sapiens_assembly38.fasta.64.alt"
   :ref_fasta       "gs://gcp-public-data--broad-references/hg38/v0/Homo_sapiens_assembly38.fasta"
   :ref_sa          "gs://gcp-public-data--broad-references/hg38/v0/Homo_sapiens_assembly38.fasta.64.sa"
   :ref_amb         "gs://gcp-public-data--broad-references/hg38/v0/Homo_sapiens_assembly38.fasta.64.amb"})

(def references-defaults
  {:calling_interval_list    "gs://gcp-public-data--broad-references/hg38/v0/exome_calling_regions.v1.interval_list"
   :contamination_sites_bed  "gs://gcp-public-data--broad-references/hg38/v0/contamination-resources/1000g/1000g.phase3.100k.b38.vcf.gz.dat.bed"
   :contamination_sites_mu   "gs://gcp-public-data--broad-references/hg38/v0/contamination-resources/1000g/1000g.phase3.100k.b38.vcf.gz.dat.mu"
   :contamination_sites_ud   "gs://gcp-public-data--broad-references/hg38/v0/contamination-resources/1000g/1000g.phase3.100k.b38.vcf.gz.dat.UD"
   :dbsnp_vcf                "gs://gcp-public-data--broad-references/hg38/v0/Homo_sapiens_assembly38.dbsnp138.vcf"
   :dbsnp_vcf_index          "gs://gcp-public-data--broad-references/hg38/v0/Homo_sapiens_assembly38.dbsnp138.vcf.idx"
   :evaluation_interval_list "gs://gcp-public-data--broad-references/hg38/v0/exome_evaluation_regions.v1.interval_list"
   :haplotype_database_file  "gs://gcp-public-data--broad-references/hg38/v0/Homo_sapiens_assembly38.haplotype_database.txt"
   :known_indels_sites_vcfs
                             ["gs://gcp-public-data--broad-references/hg38/v0/Mills_and_1000G_gold_standard.indels.hg38.vcf.gz"
                              "gs://gcp-public-data--broad-references/hg38/v0/Homo_sapiens_assembly38.known_indels.vcf.gz"]
   :known_indels_sites_indices
                             ["gs://gcp-public-data--broad-references/hg38/v0/Mills_and_1000G_gold_standard.indels.hg38.vcf.gz.tbi"
                              "gs://gcp-public-data--broad-references/hg38/v0/Homo_sapiens_assembly38.known_indels.vcf.gz.tbi"]
   :reference_fasta          reference-fasta-defaults})

(def scatter-settings-defaults
  {:haplotype_scatter_count     50
   :break_bands_at_multiples_of 0})

(def papi-settings-defaults
  {:agg_preemptible_tries 3
   :preemptible_tries     3})

(def workflow-defaults
  {:unmapped_bam_suffix  ".unmapped.bam"
   :cram_ref_fasta       "gs://gcp-public-data--broad-references/hg38/v0/Homo_sapiens_assembly38.fasta"
   :cram_ref_fasta_index "gs://gcp-public-data--broad-references/hg38/v0/Homo_sapiens_assembly38.fasta.fai"
   :bait_set_name        "whole_exome_illumina_coding_v1"
   :bait_interval_list   "gs://broad-references-private/whole_exome_illumina_coding_v1/whole_exome_illumina_coding_v1.Homo_sapiens_assembly38.baits.interval_list"
   :target_interval_list "gs://broad-references-private/whole_exome_illumina_coding_v1/whole_exome_illumina_coding_v1.Homo_sapiens_assembly38.targets.interval_list"
   :references           references-defaults
   :scatter_settings     scatter-settings-defaults
   :papi_settings        papi-settings-defaults})

(defn normalize-input-items
  "The `items` of this workload are either a bucket or a list of samples.
  Normalise these `items` into a list of samples"
  [items]
  (if (string? items)
    (let [[bucket object] (gcs/parse-gs-url items)]
      (letfn [(bam-or-cram-ify [{:keys [name]}]
                (let [url (gcs/gs-url bucket name)]
                  (cond (str/ends-with? name ".bam") {:input_bam url}
                        (str/ends-with? name ".cram") {:input_cram url})))]
        (->>
          (gcs/list-objects bucket object)
          (map bam-or-cram-ify)
          (remove nil?))))
    items))

(defn make-persisted-inputs [output-url common inputs]
  (let [sample-name (fn [basename] (first (str/split basename #"\.")))
        [_ path] (gcs/parse-gs-url (some inputs [:input_bam :input_cram]))
        basename    (or (:base_file_name inputs) (util/basename path))]
    (->
      (util/deep-merge common inputs)
      (assoc :base_file_name basename)
      (util/assoc-when util/absent? :sample_name (sample-name basename))
      (util/assoc-when util/absent? :final_gvcf_base_name basename)
      (util/assoc-when util/absent? :destination_cloud_path
        (str (all/slashify output-url) (util/dirname path))))))

(defn- get-cromwell-environment [workload]
  (first
    (all/cromwell-environments
      #{:gotc-dev :gotc-prod :gotc-staging}
      (:cromwell workload))))

(defn- make-workflow-inputs [environment persisted-inputs]
  (->
    (env/stuff environment)
    (select-keys [:google_account_vault_path :vault_token_path])
    (merge (util/deep-merge workflow-defaults persisted-inputs))))

; visible for testing
(defn submit-workload! [{:keys [uuid workflows] :as workload}]
  (letfn [(update-workflow [workflow cromwell-uuid]
            (assoc workflow :uuid cromwell-uuid
                            :status "Submitted"             ; we've just submitted it
                            :updated (OffsetDateTime/now)))
          (add-prefix [inputs] (util/prefix-keys inputs (keyword pipeline)))]
    (let [path        (wdl/hack-unpack-resources-hack (:top workflow-wdl))
          environment (get-cromwell-environment workload)]
      (logr/infof "submitting workload %s" uuid)
      (mapv update-workflow
        workflows
        (cromwell/submit-workflows
          environment
          (io/file (:dir path) (path ".wdl"))
          (io/file (:dir path) (path ".zip"))
          (map (comp add-prefix :inputs) workflows)
          (util/make-options environment)
          (merge cromwell-labels {:workload uuid}))))))

(defn create-xx-workload!
  [tx {:keys [output common_inputs items] :as request}]
  (let [[uuid table] (all/add-workload-table! tx workflow-wdl request)]
    (letfn [(make-workflow-record [id items]
              (->>
                (make-persisted-inputs output common_inputs items)
                json/write-str
                (assoc {:id id} :inputs)))]
      (->>
        (normalize-input-items items)
        (mapv make-workflow-record (range))
        (jdbc/insert-multi! tx table))
      (workloads/load-workload-for-uuid tx uuid))))

(defn start-xx-workload!
  [tx {:keys [items id] :as workload}]
  (if (:started workload)
    workload
    (letfn [(update-record! [{:keys [id] :as workflow}]
              (let [values (select-keys workflow [:uuid :status :updated])]
                (jdbc/update! tx items values ["id = ?" id])))]
      (let [now (OffsetDateTime/now)]
        (run! update-record! (submit-workload! workload))
        (jdbc/update! tx :workload {:started now} ["id = ?" id]))
      (workloads/load-workload-for-id tx id))))

(defmethod workloads/load-workload-impl
  pipeline
  [tx {:keys [items] :as workload}]
  (let [unnilify     (fn [x] (into {} (filter second x)))
        environment  (get-cromwell-environment workload)
        load-inputs! #(make-workflow-inputs environment (util/parse-json %))]
    (->>
      (postgres/get-table tx items)
      (mapv (comp #(update % :inputs load-inputs!) unnilify))
      (assoc workload :workflows)
      unnilify)))

(defoverload workloads/create-workload! pipeline create-xx-workload!)
(defoverload workloads/start-workload! pipeline start-xx-workload!)

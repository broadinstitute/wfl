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
            [wfl.references :as references]
            [wfl.service.gcs :as gcs]
            [wfl.service.postgres :as postgres]
            [wfl.service.cromwell :as cromwell]
            [wfl.util :as util]
            [wfl.wdl :as wdl]
            [wfl.wfl :as wfl])
  (:import [java.time OffsetDateTime]))

(def pipeline "ExternalExomeReprocessing")

(def ^:private cromwell-labels
  "The WDL label applied to Cromwell metadata."
  {(keyword wfl/the-name) pipeline})

(def workflow-wdl
  "The top-level WDL file and its version."
  {:release "ExternalExomeReprocessing_v2.1.1"
   :top     "pipelines/broad/reprocessing/external/exome/ExternalExomeReprocessing.wdl"})

(def ^:private references-defaults
  (let [hg38 (partial str "gs://gcp-public-data--broad-references/hg38/v0/")]
    {:calling_interval_list      (hg38 "exome_calling_regions.v1.interval_list")
     :contamination_sites_bed    (hg38 "contamination-resources/1000g/1000g.phase3.100k.b38.vcf.gz.dat.bed")
     :contamination_sites_mu     (hg38 "contamination-resources/1000g/1000g.phase3.100k.b38.vcf.gz.dat.mu")
     :contamination_sites_ud     (hg38 "contamination-resources/1000g/1000g.phase3.100k.b38.vcf.gz.dat.UD")
     :dbsnp_vcf                  (hg38 "Homo_sapiens_assembly38.dbsnp138.vcf")
     :dbsnp_vcf_index            (hg38 "Homo_sapiens_assembly38.dbsnp138.vcf.idx")
     :evaluation_interval_list   (hg38 "exome_evaluation_regions.v1.interval_list")
     :haplotype_database_file    (hg38 "Homo_sapiens_assembly38.haplotype_database.txt")
     :known_indels_sites_vcfs    [(hg38 "Mills_and_1000G_gold_standard.indels.hg38.vcf.gz")
                                  (hg38 "Homo_sapiens_assembly38.known_indels.vcf.gz")]
     :known_indels_sites_indices [(hg38 "Mills_and_1000G_gold_standard.indels.hg38.vcf.gz.tbi")
                                  (hg38 "Homo_sapiens_assembly38.known_indels.vcf.gz.tbi")]
     :reference_fasta            (references/reference_fasta)}))

(def ^:private scatter_settings-defaults
  {:haplotype_scatter_count     50
   :break_bands_at_multiples_of 0})

(def ^:private papi_settings-defaults
  {:agg_preemptible_tries 3
   :preemptible_tries     3})

(def ^:private workflow-defaults
  (let [hg38 (partial str "gs://gcp-public-data--broad-references/hg38/v0/")]
    {:unmapped_bam_suffix  ".unmapped.bam"
     :cram_ref_fasta       (hg38 "Homo_sapiens_assembly38.fasta")
     :cram_ref_fasta_index (hg38 "Homo_sapiens_assembly38.fasta.fai")
     :bait_set_name        "whole_exome_illumina_coding_v1"
     :bait_interval_list   (hg38 "HybSelOligos/whole_exome_illumina_coding_v1/whole_exome_illumina_coding_v1.Homo_sapiens_assembly38.baits.interval_list")
     :target_interval_list (hg38 "HybSelOligos/whole_exome_illumina_coding_v1/whole_exome_illumina_coding_v1.Homo_sapiens_assembly38.targets.interval_list")
     :references           references-defaults
     :scatter_settings     scatter_settings-defaults
     :papi_settings        papi_settings-defaults}))

(defn ^:private get-cromwell-environment [{:keys [cromwell]}]
  (let [envs (all/cromwell-environments #{:xx-dev :xx-prod} cromwell)]
    (when (not= 1 (count envs))
      (throw (ex-info "no unique environment matching Cromwell URL."
               {:cromwell     cromwell
                :environments envs})))
    (first envs)))

(defn ^:private cromwellify-inputs [environment inputs]
  (-> (env/stuff environment)
    (select-keys [:google_account_vault_path :vault_token_path])
    (merge inputs)
    (util/prefix-keys (keyword pipeline))))

;; visible for testing
;; Note: the database stores per-workflow inputs so we need to combine
;; any `common-inputs` with these before we commit them to storage.
(defn make-combined-inputs-to-save [output-url common-inputs inputs]
  (let [sample-name (fn [basename] (first (str/split basename #"\.")))
        [_ path] (gcs/parse-gs-url (some inputs [:input_bam :input_cram]))
        basename    (or (:base_file_name inputs) (util/basename path))]
    (-> (util/deep-merge common-inputs inputs)
      (assoc :base_file_name basename)
      (util/assoc-when util/absent? :sample_name (sample-name basename))
      (util/assoc-when util/absent? :final_gvcf_base_name basename)
      (util/assoc-when util/absent? :destination_cloud_path
        (str (all/slashify output-url) (util/dirname path))))))

;; visible for testing
(defn submit-workload! [{:keys [uuid workflows] :as workload}]
  (letfn [(update-workflow [workflow cromwell-uuid]
            (assoc workflow :uuid cromwell-uuid
                            :status "Submitted"
                            :updated (OffsetDateTime/now)))]
    (let [path        (wdl/hack-unpack-resources-hack (:top workflow-wdl))
          environment (get-cromwell-environment workload)]
      (logr/infof "submitting workload %s" uuid)
      (mapv update-workflow
        workflows
        (cromwell/submit-workflows
          environment
          (io/file (:dir path) (path ".wdl"))
          (io/file (:dir path) (path ".zip"))
          (map (comp (partial cromwellify-inputs environment) :inputs) workflows)
          (util/make-options environment)
          (merge cromwell-labels {:workload uuid}))))))

(defn create-xx-workload! [tx {:keys [output common_inputs items] :as request}]
  (letfn [(make-workflow-record [id inputs]
            (->> (make-combined-inputs-to-save output common_inputs inputs)
              json/write-str
              (assoc {:id id} :inputs)))]
    (let [default-options (util/make-options (get-cromwell-environment request))
          [uuid table]    (all/add-workload-table! tx workflow-wdl request default-options)]
      (->> (map make-workflow-record (range) (map :inputs items))
        (jdbc/insert-multi! tx table))
      (workloads/load-workload-for-uuid tx uuid))))

(defn start-xx-workload! [tx {:keys [items id] :as workload}]
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
  (letfn [(unnilify [m] (into {} (filter second m)))
          (load-inputs! [workflow]
            (util/deep-merge workflow-defaults (util/parse-json workflow)))
          (unpack-options [m]
            (update m :workflow_options #(util/parse-json (or % "{}"))))]
    (->> (postgres/get-table tx items)
         (mapv (comp #(update % :inputs load-inputs!) unnilify))
         (assoc workload :workflows)
         unnilify
         unpack-options)))

(defoverload workloads/create-workload! pipeline create-xx-workload!)
(defoverload workloads/start-workload! pipeline start-xx-workload!)

(ns wfl.module.xx
  "Reprocess External Exomes, whatever they are."
  (:require [clojure.data.json :as json]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [wfl.api.workloads :refer [defoverload]]
            [wfl.api.workloads :as workloads]
            [wfl.environments :as env]
            [wfl.jdbc :as jdbc]
            [wfl.module.all :as all]
            [wfl.module.batch :as batch]
            [wfl.references :as references]
            [wfl.service.gcs :as gcs]
            [wfl.service.cromwell :as cromwell]
            [wfl.util :as util]
            [wfl.wdl :as wdl]
            [wfl.wfl :as wfl])
  (:import [java.time OffsetDateTime]))

(def pipeline "ExternalExomeReprocessing")

(def ^:private cromwell-label
  "The WDL label applied to Cromwell metadata."
  {(keyword wfl/the-name) pipeline})

(def workflow-wdl
  "The top-level WDL file and its version."
  {:release "ExternalExomeReprocessing_v2.1.1"
   :top     (str "pipelines/broad/reprocessing/external/exome/"
                 "ExternalExomeReprocessing.wdl")})

(def ^:private references-defaults
  (let [hg38 "gs://gcp-public-data--broad-references/hg38/v0/"
        hsa  "Homo_sapiens_assembly38"]
    (merge references/hg38-exome-references
           references/contamination-sites
           {:calling_interval_list   (str hg38 "exome_calling_regions.v1.interval_list")
            :haplotype_database_file (str hg38 hsa ".haplotype_database.txt")})))

(def ^:private workflow-defaults
  (let [hg38          "gs://gcp-public-data--broad-references/hg38/v0/"
        hsa           "Homo_sapiens_assembly38"
        bait_set_name "whole_exome_illumina_coding_v1"
        HybSelOligos  (str/join "/" ["HybSelOligos" bait_set_name bait_set_name])
        iv1           (str/join "." [HybSelOligos hsa])]
    {:unmapped_bam_suffix  ".unmapped.bam"
     :cram_ref_fasta       (str hg38 hsa ".fasta")
     :cram_ref_fasta_index (str hg38 hsa ".fasta.fai")
     :bait_set_name        bait_set_name
     :bait_interval_list   (str hg38 iv1 ".baits.interval_list")
     :target_interval_list (str hg38 iv1 ".targets.interval_list")
     :references           references-defaults
     :scatter_settings     {:break_bands_at_multiples_of 0
                            :haplotype_scatter_count     50}
     :papi_settings        {:agg_preemptible_tries 3
                            :preemptible_tries     3}}))

;; visible for testing
(defn get-cromwell-environment [{:keys [cromwell]}]
  (let [cromwell (all/de-slashify cromwell)
        envs     (all/cromwell-environments #{:xx-dev :xx-prod} cromwell)]
    (when (not= 1 (count envs))
      (throw (ex-info "no unique environment matching Cromwell URL."
                      {:cromwell     cromwell
                       :environments envs})))
    (first envs)))

(defn ^:private cromwellify-workflow-inputs [environment {:keys [inputs]}]
  (-> (env/stuff environment)
      (select-keys [:google_account_vault_path :vault_token_path])
      (util/deep-merge workflow-defaults)
      (util/deep-merge inputs)
      (util/prefix-keys (keyword pipeline))))

;; visible for testing
(defn make-inputs-to-save [output-url inputs]
  (let [sample (some inputs [:input_bam :input_cram])
        [_ base _] (all/bam-or-cram? sample)
        leaf   (util/leafname base)
        [_ out-dir] (gcs/parse-gs-url base)]
    (-> inputs
        (util/assoc-when util/absent? :base_file_name leaf)
        (util/assoc-when util/absent? :sample_name leaf)
        (util/assoc-when util/absent? :final_gvcf_base_name leaf)
        (util/assoc-when util/absent? :destination_cloud_path
                         (str (all/slashify output-url) (util/dirname out-dir))))))

(defn create-xx-workload!
  [tx {:keys [common items output] :as request}]
  (letfn [(nil-if-empty [x] (if (empty? x) nil x))
          (merge-to-json [shared specific]
            (json/write-str (nil-if-empty (util/deep-merge shared specific))))
          (serialize [item id]
            (-> item
                (assoc :id id)
                (update :options #(merge-to-json (:options common) %))
                (update :inputs #(merge-to-json (:inputs common)
                                                (make-inputs-to-save output %)))))]
    (let [[id table] (batch/add-workload-table! tx workflow-wdl request)]
      (jdbc/insert-multi! tx table (map serialize items (range)))
      (workloads/load-workload-for-id tx id))))

(defn start-xx-workload! [tx {:keys [items id] :as workload}]
  (letfn [(update-record! [{:keys [id] :as workflow}]
            (let [values (select-keys workflow [:uuid :status :updated])]
              (jdbc/update! tx items values ["id = ?" id])))]
    (let [now (OffsetDateTime/now)
          env (get-cromwell-environment workload)]
      (run! update-record! (batch/submit-workload! workload env workflow-wdl cromwellify-workflow-inputs cromwell-label))
      (jdbc/update! tx :workload {:started now} ["id = ?" id]))
    (workloads/load-workload-for-id tx id)))

(defoverload workloads/create-workload! pipeline create-xx-workload!)
(defoverload workloads/start-workload! pipeline start-xx-workload!)
(defoverload workloads/load-workload-impl pipeline batch/load-batch-workload-impl)

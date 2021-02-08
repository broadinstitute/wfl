(ns wfl.module.xx
  "Reprocess External Exomes, whatever they are."
  (:require [clojure.data.json :as json]
            [clojure.string :as str]
            [wfl.api.workloads :refer [defoverload]]
            [wfl.api.workloads :as workloads]
            [wfl.jdbc :as jdbc]
            [wfl.module.all :as all]
            [wfl.module.batch :as batch]
            [wfl.references :as references]
            [wfl.service.google.storage :as gcs]
            [wfl.util :as util]
            [wfl.wfl :as wfl])
  (:import [java.time OffsetDateTime]))

(def pipeline "ExternalExomeReprocessing")

(def ^:private cromwell-label
  "The WDL label applied to Cromwell metadata."
  {(keyword wfl/the-name) pipeline})

(def workflow-wdl
  "The top-level WDL file and its version."
  {:release "ExternalExomeReprocessing_v2.1.1"
   :path     (str "pipelines/broad/reprocessing/external/exome/"
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

(def ^:private known-cromwells
  ["https://cromwell-gotc-auth.gotc-dev.broadinstitute.org"
   "https://cromwell-gotc-auth.gotc-prod.broadinstitute.org"])

(def ^:private inputs+options
  [{:cromwell                  {:labels            [:data_type
                                                    :project
                                                    :regulatory_designation
                                                    :sample_name
                                                    :version]
                                :monitoring_script "gs://broad-gotc-prod-cromwell-monitoring/monitoring.sh"
                                :url               "https://cromwell-gotc-auth.gotc-dev.broadinstitute.org"}
    :google
    {:jes_roots ["gs://broad-gotc-dev-cromwell-execution"]
     :noAddress false
     :projects  ["broad-exomes-dev1"]}
    :google_account_vault_path "secret/dsde/gotc/dev/picard/picard-account.pem"
    :vault_token_path          "gs://broad-dsp-gotc-dev-tokens/picardsa.token"}
   (let [prefix   "broad-realign-"
         projects (map (partial str prefix "execution0") (range 1 6))
         buckets  (map (partial str prefix "short-execution") (range 1 11))
         roots    (map (partial format "gs://%s/") buckets)]
     {:cromwell                  {:labels            [:data_type
                                                      :project
                                                      :regulatory_designation
                                                      :sample_name
                                                      :version]
                                  :monitoring_script "gs://broad-gotc-prod-cromwell-monitoring/monitoring.sh"
                                  :url               "https://cromwell-gotc-auth.gotc-prod.broadinstitute.org"}
      :google
      {:jes_roots (vec roots)
       :noAddress false
       :projects  (vec projects)}
      :google_account_vault_path "secret/dsde/gotc/prod/picard/picard-account.pem"
      :vault_token_path          "gs://broad-dsp-gotc-prod-tokens/picardsa.token"})])

(defn ^:private cromwell->inputs+options
  "Map cromwell URL to workflow inputs and options for submitting an Exome workflow."
  [url]
  ((zipmap known-cromwells inputs+options) (util/de-slashify url)))

(defn ^:private is-known-cromwell-url?
  [url]
  (if-let [known-url (->> url
                          util/de-slashify
                          ((set known-cromwells)))]
    known-url
    (throw (ex-info "Unknown Cromwell URL provided."
                    {:cromwell url
                     :known-cromwells known-cromwells}))))

;; visible for testing
(defn make-workflow-options
  "Make workflow options to run the workflow in Cromwell URL."
  [url]
  (letfn [(maybe [m k v] (if-some [kv (k v)] (assoc m k kv) m))]
    (let [gcr   "us.gcr.io"
          repo  "broad-gotc-prod"
          image "genomes-in-the-cloud:2.4.3-1564508330"
          {:keys [cromwell google]} (cromwell->inputs+options url)
          {:keys [projects jes_roots noAddress]} google]
      (-> {:backend         "PAPIv2"
           :google_project  (rand-nth projects)
           :jes_gcs_root    (rand-nth jes_roots)
           :read_from_cache true
           :write_to_cache  true
           :default_runtime_attributes
           {:docker (str/join "/" [gcr repo image])
            :zones  util/google-cloud-zones
            :maxRetries 1}}
          (maybe :monitoring_script cromwell)
          (maybe :noAddress noAddress)))))

(defn ^:private cromwellify-workflow-inputs
  [url {:keys [inputs]}]
  (-> (cromwell->inputs+options url)
      (select-keys [:google_account_vault_path :vault_token_path])
      (util/deep-merge workflow-defaults)
      (util/deep-merge inputs)
      (util/prefix-keys (keyword pipeline))))

;; visible for testing
(defn make-inputs-to-save [output-url inputs]
  (let [sample      (some inputs [:input_bam :input_cram])
        base        (util/remove-extension sample)
        basename    (util/basename base)
        [_ out-dir] (gcs/parse-gs-url (util/unsuffix base basename))]
    (-> inputs
        (util/assoc-when util/absent? :base_file_name basename)
        (util/assoc-when util/absent? :sample_name basename)
        (util/assoc-when util/absent? :final_gvcf_base_name basename)
        (util/assoc-when util/absent? :destination_cloud_path
                         (str (util/slashify output-url) (util/dirname out-dir))))))

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

;; TODO: move the URL validation up to workload creation
;;
(defn start-xx-workload! [tx {:keys [items id executor] :as workload}]
  (letfn [(update-record! [{:keys [id] :as workflow}]
            (let [values (select-keys workflow [:uuid :status :updated])]
              (jdbc/update! tx items values ["id = ?" id])))]
    (let [now (OffsetDateTime/now)
          executor (is-known-cromwell-url? executor)]
      (run! update-record! (batch/submit-workload! workload executor workflow-wdl cromwellify-workflow-inputs cromwell-label (make-workflow-options executor)))
      (jdbc/update! tx :workload {:started now} ["id = ?" id]))
    (workloads/load-workload-for-id tx id)))

(defoverload workloads/create-workload! pipeline create-xx-workload!)
(defoverload workloads/start-workload! pipeline start-xx-workload!)
(defoverload workloads/update-workload! pipeline batch/update-workload!)
(defoverload workloads/load-workload-impl pipeline batch/load-batch-workload-impl)

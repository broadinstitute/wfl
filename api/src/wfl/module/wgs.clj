(ns wfl.module.wgs
  "Reprocess (External) Whole Genomes."
  (:require [clojure.data.json :as json]
            [clojure.string :as str]
            [wfl.api.workloads :as workloads :refer [defoverload]]
            [wfl.jdbc :as jdbc]
            [wfl.module.all :as all]
            [wfl.references :as references]
            [wfl.service.gcs :as gcs]
            [wfl.util :as util]
            [wfl.wfl :as wfl]
            [wfl.module.batch :as batch])
  (:import [java.time OffsetDateTime]))

(def pipeline "ExternalWholeGenomeReprocessing")

(def workflow-wdl
  "The top-level WDL file and its version."
  {:release "ExternalWholeGenomeReprocessing_v1.1.1"
   :path    "pipelines/broad/reprocessing/external/wgs/ExternalWholeGenomeReprocessing.wdl"})

(def ^:private cromwell-label
  {(keyword wfl/the-name) pipeline})

(def cram-ref
  "Ref Fasta for CRAM."
  (let [hg38           "gs://gcp-public-data--broad-references/hg38/v0/"
        cram_ref_fasta (str hg38 "Homo_sapiens_assembly38.fasta")]
    {:cram_ref_fasta       cram_ref_fasta
     :cram_ref_fasta_index (str cram_ref_fasta ".fai")}))

(def ^:private default-references
  "HG38 reference, calling interval, and contamination files."
  (let [hg38 "gs://gcp-public-data--broad-references/hg38/v0/"]
    (merge references/contamination-sites
           references/hg38-genome-references
           {:calling_interval_list
            (str hg38 "wgs_calling_regions.hg38.interval_list")})))

(def hack-task-level-values
  "Hack to overload task-level values for wgs pipeline."
  (let [hg38 "gs://gcp-public-data--broad-references/hg38/v0/"]
    {:wgs_coverage_interval_list
     (str hg38 "wgs_coverage_regions.hg38.interval_list")}))

(defn cromwell->inputs+options
  "Map cromwell URL to workflow inputs and options for submitting a WGS pipeline."
  [url]
  ({"https://cromwell-gotc-auth.gotc-dev.broadinstitute.org"
    {:google_account_vault_path "secret/dsde/gotc/dev/picard/picard-account.pem"
     :vault_token_path "gs://broad-dsp-gotc-dev-tokens/picardsa.token"}
    "https://cromwell-gotc-auth.gotc-prod.broadinstitute.org"
    {:google_account_vault_path "secret/dsde/gotc/prod/picard/picard-account.pem"
     :vault_token_path "gs://broad-dsp-gotc-prod-tokens/picardsa.token"}}
   (util/de-slashify url)))

(defn static-inputs
  "Static inputs for Cromwell URL that do not depend on the inputs."
  [url]
  (let [{:keys [google_account_vault_path vault_token_path]}
        (cromwell->inputs+options url)]
    {:google_account_vault_path google_account_vault_path
     :vault_token_path          vault_token_path
     :papi_settings             {:agg_preemptible_tries 3
                                 :preemptible_tries     3}
     :scatter_settings          {:haplotype_scatter_count     10
                                 :break_bands_at_multiples_of 100000}}))

(defn ^:private normalize-reference-fasta [inputs]
  (if-let [prefix (:reference_fasta_prefix inputs)]
    (-> (update-in inputs [:references :reference_fasta]
                   #(util/deep-merge (references/reference_fasta prefix) %))
        (dissoc :reference_fasta_prefix))
    inputs))

(defn ^:private make-inputs-to-save
  "Return inputs for reprocessing IN-GS into OUT-GS."
  [out-gs inputs]
  (let [sample (some inputs [:input_bam :input_cram])
        [_ base _] (all/bam-or-cram? sample)
        leaf   (util/leafname base)
        [_ out-dir] (gcs/parse-gs-url (util/unsuffix base leaf))]
    (-> inputs
        (util/assoc-when util/absent? :base_file_name leaf)
        (util/assoc-when util/absent? :sample_name leaf)
        (util/assoc-when util/absent? :unmapped_bam_suffix ".unmapped.bam")
        (util/assoc-when util/absent? :final_gvcf_base_name leaf)
        (assoc :destination_cloud_path (str out-gs out-dir)))))

(defn ^:private make-workflow-inputs
  "Make the final pipeline inputs from Cromwell URL."
  [url {:keys [inputs]}]
  (-> (util/deep-merge cram-ref
                       hack-task-level-values
                       {:references default-references}
                       (static-inputs url)
                       inputs)
      (util/prefix-keys (keyword pipeline))))

(def ^:private known-cromwells
  ["https://cromwell-gotc-auth.gotc-dev.broadinstitute.org"
   "https://cromwell-gotc-auth.gotc-prod.broadinstitute.org"])

(def ^:private inputs+options
  [{:labels                    [:data_type
                                :project
                                :regulatory_designation
                                :sample_name
                                :version]
    :google
    {:jes_roots ["gs://broad-gotc-dev-cromwell-execution"]
     :noAddress false
     :projects  ["broad-exomes-dev1"]}
    :server
    {:project "broad-gotc-dev"
     :vault   "secret/dsde/gotc/dev/zero"}
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
  "Map cromwell URL to workflow inputs and options for submitting an Whole Genome workflow."
  [url]
  ((zipmap known-cromwells inputs+options) (util/de-slashify url)))

(defn ^:private make-workflow-options
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

(defn ^:private is-known-cromwell-url?
  [url]
  (if-let [known-url (->> url
                          util/de-slashify
                          ((set known-cromwells)))]
    known-url
    (throw (ex-info "Unknown Cromwell URL provided."
                    {:cromwell url}))))

(defn create-wgs-workload!
  "Use transaction TX to add the workload described by REQUEST."
  [tx {:keys [items output common] :as request}]
  (letfn [(nil-if-empty [x] (if (empty? x) nil x))
          (serialize [workflow id]
            (-> (assoc workflow :id id)
                (update :options
                        #(json/write-str
                          (nil-if-empty (util/deep-merge (:options common) %))))
                (update :inputs
                        #(json/write-str
                          (normalize-reference-fasta
                           (util/deep-merge
                            (:inputs common)
                            (make-inputs-to-save output %)))))))]
    (let [[id table] (batch/add-workload-table! tx workflow-wdl request)]
      (jdbc/insert-multi! tx table (map serialize items (range)))
      (workloads/load-workload-for-id tx id))))

(defoverload workloads/create-workload! pipeline create-wgs-workload!)

;; TODO: move the URL validation up to workload creation
;;
(defmethod workloads/start-workload!
  pipeline
  [tx {:keys [items id executor] :as workload}]
  (letfn [(update-record! [{:keys [id] :as workflow}]
            (let [values (select-keys workflow [:uuid :status :updated])]
              (jdbc/update! tx items values ["id = ?" id])))]
    (let [now (OffsetDateTime/now)
          executor (is-known-cromwell-url? executor)]
      (run! update-record! (batch/submit-workload! workload executor workflow-wdl make-workflow-inputs cromwell-label (make-workflow-options executor)))
      (jdbc/update! tx :workload {:started now} ["id = ?" id]))
    (workloads/load-workload-for-id tx id)))

(defoverload workloads/update-workload! pipeline batch/update-workload!)

(defmethod workloads/load-workload-impl
  pipeline
  [tx workload]
  (if (workloads/saved-before? "0.4.0" workload)
    (workloads/default-load-workload-impl tx workload)
    (batch/load-batch-workload-impl tx workload)))

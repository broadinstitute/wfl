(ns wfl.module.wgs
  "Reprocess (External) Whole Genomes."
  (:require [clojure.data.json :as json]
            [wfl.api.workloads :as workloads :refer [defoverload]]
            [wfl.environments :as env]
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

(defn get-cromwell-environment [{:keys [executor]}]
  (let [envs (all/cromwell-environments #{:wgs-dev :wgs-prod} executor)]
    (when (not= 1 (count envs))
      (throw (ex-info "No unique environment matching Cromwell URL."
                      {:cromwell     executor
                       :environments envs})))
    (first envs)))

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

(defn env-inputs
  "Genome inputs for ENVIRONMENT that do not depend on the input file."
  [environment]
  (let [{:keys [google_account_vault_path vault_token_path]}
        (env/stuff environment)]
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

(defn ^:private make-cromwell-inputs
  [environment {:keys [inputs]}]
  (-> (util/deep-merge cram-ref
                       hack-task-level-values
                       {:references default-references}
                       (env-inputs environment)
                       inputs)
      (util/prefix-keys (keyword pipeline))))

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

(defmethod workloads/start-workload!
  pipeline
  [tx {:keys [items id] :as workload}]
  (letfn [(update-record! [{:keys [id] :as workflow}]
            (let [values (select-keys workflow [:uuid :status :updated])]
              (jdbc/update! tx items values ["id = ?" id])))]
    (let [now (OffsetDateTime/now)
          env (get-cromwell-environment workload)]
      (run! update-record! (batch/submit-workload! workload env workflow-wdl make-cromwell-inputs cromwell-label))
      (jdbc/update! tx :workload {:started now} ["id = ?" id]))
    (workloads/load-workload-for-id tx id)))

(defoverload workloads/update-workload! pipeline batch/update-workload!)

(defmethod workloads/load-workload-impl
  pipeline
  [tx workload]
  (if (workloads/saved-before? "0.4.0" workload)
    (workloads/default-load-workload-impl tx workload)
    (batch/load-batch-workload-impl tx workload)))

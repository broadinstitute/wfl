(ns wfl.module.wgs
  "Reprocess (External) Whole Genomes."
  (:require [clojure.java.io :as io]
            [clojure.data.json :as json]
            [wfl.api.workloads :as workloads]
            [wfl.environments :as env]
            [wfl.jdbc :as jdbc]
            [wfl.module.all :as all]
            [wfl.references :as references]
            [wfl.service.cromwell :as cromwell]
            [wfl.service.gcs :as gcs]
            [wfl.util :as util]
            [wfl.wdl :as wdl]
            [wfl.wfl :as wfl]
            [wfl.module.batch :as batch])
  (:import [java.time OffsetDateTime]))

(def pipeline "ExternalWholeGenomeReprocessing")

(def workflow-wdl
  "The top-level WDL file and its version."
  {:release "ExternalWholeGenomeReprocessing_v1.1.1"
   :top     "pipelines/broad/reprocessing/external/wgs/ExternalWholeGenomeReprocessing.wdl"})

(def cromwell-label-map
  "The WDL label applied to Cromwell metadata."
  {(keyword wfl/the-name)
   pipeline})

(def cromwell-label
  "The WDL label applied to Cromwell metadata."
  (let [[key value] (first cromwell-label-map)]
    (str (name key) ":" value)))

(defn ^:private get-cromwell-environment [{:keys [cromwell]}]
  (let [envs (all/cromwell-environments #{:wgs-dev :wgs-prod} cromwell)]
    (when (not= 1 (count envs))
      (throw (ex-info "no unique environment matching Cromwell URL."
               {:cromwell     cromwell
                :environments envs})))
    (first envs)))

(def cram-ref
  "Ref Fasta for CRAM."
  (let [hg38           "gs://gcp-public-data--broad-references/hg38/v0/"
        cram_ref_fasta (str hg38 "Homo_sapiens_assembly38.fasta")]
    {:cram_ref_fasta       cram_ref_fasta
     :cram_ref_fasta_index (str cram_ref_fasta ".fai")}))

(defn make-references
  "HG38 reference, calling interval, and contamination files."
  [prefix]
  (let [hg38 "gs://gcp-public-data--broad-references/hg38/v0/"]
    (merge references/contamination-sites
      (references/hg38-genome-references prefix)
      {:calling_interval_list
       (str hg38 "wgs_calling_regions.hg38.interval_list")})))

(def hack-task-level-values
  "Hack to overload task-level values for wgs pipeline."
  (let [hg38 "gs://gcp-public-data--broad-references/hg38/v0/"]
    (merge {:wgs_coverage_interval_list
            (str hg38 "wgs_coverage_regions.hg38.interval_list")}
      (-> {:disable_sanity_check true}
        (util/prefix-keys :CheckContamination)
        (util/prefix-keys :UnmappedBamToAlignedBam)
        (util/prefix-keys :WholeGenomeGermlineSingleSample)
        (util/prefix-keys :WholeGenomeReprocessing)))))

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

(defn ^:private normalize-references [inputs]
  (update inputs :references
    #(util/deep-merge
       (-> inputs :reference_fasta_prefix make-references)
       %)))

(defn ^:private make-inputs-to-save
  "Return inputs for reprocessing IN-GS into OUT-GS."
  [out-gs inputs]
  (let [sample (some inputs [:input_bam :input_cram])
        [_ base _] (all/bam-or-cram? sample)
        leaf   (util/basename base)
        [_ out-dir] (gcs/parse-gs-url (util/unsuffix base leaf))]
    (-> inputs
      (util/assoc-when util/absent? :base_file_name leaf)
      (util/assoc-when util/absent? :sample_name leaf)
      (util/assoc-when util/absent? :unmapped_bam_suffix ".unmapped.bam")
      (util/assoc-when util/absent? :final_gvcf_base_name leaf)
      (assoc :destination_cloud_path (str out-gs out-dir))
      normalize-references)))

(defn ^:private make-cromwell-inputs
  "Return inputs for reprocessing IN-GS into OUT-GS in ENVIRONMENT."
  [environment workflow-inputs]
  (-> (util/deep-merge cram-ref hack-task-level-values)
    (util/deep-merge (env-inputs environment))
    (util/deep-merge workflow-inputs)
    (util/prefix-keys (keyword pipeline))))

(defn make-labels
  "Return labels for wgs pipeline from OTHER-LABELS."
  [other-labels]
  (merge cromwell-label-map other-labels))

(defn really-submit-one-workflow
  "Submit IN-GS for reprocessing into OUT-GS in ENVIRONMENT given OTHER-LABELS."
  [environment inputs options other-labels]
  (let [path (wdl/hack-unpack-resources-hack (:top workflow-wdl))]
    (cromwell/submit-workflow
      environment
      (io/file (:dir path) (path ".wdl"))
      (io/file (:dir path) (path ".zip"))
      (make-cromwell-inputs environment inputs)
      options
      (make-labels other-labels))))

(defn add-wgs-workload!
  "Use transaction TX to add the workload described by REQUEST."
  [tx {:keys [items output common] :as request}]
  (letfn [(merge-to-json [shared specific]
            (json/write-str (util/deep-merge shared specific)))
          (serialize [workflow id]
            (-> (assoc workflow :id id)
              (update :options #(merge-to-json (:options common) %))
              (update :inputs #(merge-to-json
                                 (normalize-references (:inputs common))
                                 (make-inputs-to-save output %)))))]
    (let [[id table] (batch/add-workload-table! tx workflow-wdl request)]
      (jdbc/insert-multi! tx table (map serialize items (range)))
      id)))

(defn skip-workflow?
  "True when _WORKFLOW in _WORKLOAD in ENV is done or active."
  [env
   {:keys [output] :as _workload}
   {:keys [inputs] :as _workflow}]
  (letfn [(exists? [out-gs]
            (seq (util/do-or-nil
                   (->> out-gs gcs/parse-gs-url (apply gcs/list-objects)))))
          (processing? [in-gs]
            (->> {:label cromwell-label :status cromwell/active-statuses}
              (cromwell/query :gotc-dev)
              (filter #(= pipeline (:name %)))
              (map #(->> % :id (cromwell/metadata env) :inputs))
              (map #(some % [:input_bam :input_cram]))
              (filter #{in-gs})
              seq))]
    (let [in-gs  (some inputs [:input_bam :input_cram])
          [_ object] (gcs/parse-gs-url in-gs)
          out-gs (str (all/slashify output) object)]
      (or (exists? out-gs) (processing? in-gs)))))

(defn start-wgs-workload!
  "Use transaction TX to start the WORKLOAD."
  [tx {:keys [items uuid] :as workload}]
  (let [env             (get-cromwell-environment workload)
        default-options (util/make-options env)
        workload->label {:workload uuid}]
    (letfn [(submit! [{:keys [id inputs options] :as workflow}]
              [id (if (skip-workflow? env workload workflow)
                    util/uuid-nil
                    (really-submit-one-workflow
                      env
                      inputs
                      (util/deep-merge default-options options)
                      workload->label))])
            (update! [tx [id uuid]]
              (jdbc/update! tx items
                {:status "Submitted" :updated (OffsetDateTime/now) :uuid uuid}
                ["id = ?" id]))]
      (let [now (OffsetDateTime/now)]
        (run! (comp (partial update! tx) submit!) (:workflows workload))
        (jdbc/update! tx :workload {:started now} ["uuid = ?" uuid])))))

(defmethod workloads/create-workload!
  pipeline
  [tx request]
  (->>
    (add-wgs-workload! tx request)
    (workloads/load-workload-for-id tx)))

(defmethod workloads/start-workload!
  pipeline
  [tx {:keys [id] :as workload}]
  (do
    (start-wgs-workload! tx workload)
    (workloads/load-workload-for-id tx id)))

(defmethod workloads/load-workload-impl
  pipeline
  [tx workload]
  (if (workloads/saved-before? "0.4.0" workload)
    (workloads/default-load-workload-impl tx workload)
    (batch/load-batch-workload-impl tx workload)))

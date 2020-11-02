(ns wfl.module.wgs
  "Reprocess (External) Whole Genomes."
  (:require [clojure.java.io :as io]
            [clojure.data.json :as json]
            [clojure.string :as str]
            [clojure.tools.logging.readable :as logr]
            [wfl.api.workloads :as workloads]
            [wfl.environments :as env]
            [wfl.jdbc :as jdbc]
            [wfl.module.all :as all]
            [wfl.references :as references]
            [wfl.service.cromwell :as cromwell]
            [wfl.service.gcs :as gcs]
            [wfl.service.postgres :as postgres]
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

(def get-cromwell-wgs-environment
  "Transduce Cromwell URL to a :wgs environment."
  (comp first (partial all/cromwell-environments
                #{:wgs-dev :wgs-prod :wgs-staging})))

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

(defn make-inputs
  "Return inputs for reprocessing IN-GS into OUT-GS in ENVIRONMENT."
  [environment out-gs in-gs sample]
  (let [[input-key base _] (all/bam-or-cram? in-gs)
        leaf                 (last (str/split base #"/"))
        [_ out-dir] (gcs/parse-gs-url (util/unsuffix base leaf))
        ref-prefix           (get sample :reference_fasta_prefix)
        final_gvcf_base_name (or (:final_gvcf_base_name sample) leaf)
        inputs               (-> {}
                               (assoc :base_file_name (or (:base_file_name sample) leaf))
                               (assoc :sample_name (or (:sample_name sample) leaf))
                               (assoc :unmapped_bam_suffix (or (:unmapped_bam_suffix sample) ".unmapped.bam"))
                               (assoc :final_gvcf_base_name final_gvcf_base_name)
                               (assoc input-key in-gs)
                               (assoc :destination_cloud_path (str out-gs out-dir))
                               (assoc :references (make-references ref-prefix))
                               (merge cram-ref)
                               (merge (env-inputs environment)
                                 hack-task-level-values))
        output               (str (:destination_cloud_path inputs)
                               final_gvcf_base_name
                               ".cram")]
    (all/throw-when-output-exists-already! output)
    (util/prefix-keys inputs :ExternalWholeGenomeReprocessing)))

(defn make-labels
  "Return labels for wgs pipeline from OTHER-LABELS."
  [other-labels]
  (merge cromwell-label-map
    other-labels))

(defn really-submit-one-workflow
  "Submit IN-GS for reprocessing into OUT-GS in ENVIRONMENT given OTHER-LABELS."
  [environment in-gs out-gs sample options other-labels]
  (let [path (wdl/hack-unpack-resources-hack (:top workflow-wdl))]
    (logr/infof "submitting workflow with: in-gs: %s, out-gs: %s" in-gs out-gs)
    (cromwell/submit-workflow
      environment
      (io/file (:dir path) (path ".wdl"))
      (io/file (:dir path) (path ".zip"))
      (make-inputs environment out-gs in-gs sample)
      options
      (make-labels other-labels))))

(defn add-wgs-workload!
  "Use transaction TX to add the workload described by WORKLOAD-REQUEST."
  [tx {:keys [items] :as workload-request}]
  (let [workflow-options (-> (:cromwell workload-request)
                             all/de-slashify
                             get-cromwell-wgs-environment
                             util/make-options
                             (util/deep-merge (:workflow_options workload-request)))
        [id table]       (batch/add-workload-table! tx workflow-wdl workload-request)]
    (letfn [(form [m id] (-> m
                             (update :inputs json/write-str)
                             (update :workflow_options #(json/write-str (util/deep-merge workflow-options %)))
                             (assoc :id id)))]
      (jdbc/insert-multi! tx table (map form items (range)))
      id)))

(defn skip-workflow?
  "True when _WORKFLOW in _WORKLOAD in ENV is done or active."
  [env
   {:keys [input output] :as _workload}
   {:keys [input_cram] :as _workflow}]
  (let [in-gs  (str (all/slashify input) input_cram)
        out-gs (str (all/slashify output) input_cram)]
    (or (->> out-gs gcs/parse-gs-url
          (apply gcs/list-objects)
          util/do-or-nil seq)                               ; done?
      (->> {:label  cromwell-label
            :status ["On Hold" "Running" "Submitted"]}
        (cromwell/query env)
        (map (comp :ExternalWholeGenomeReprocessing.input_cram
               (fn [it] (json/read-str it :key-fn keyword))
               :inputs :submittedFiles
               (partial cromwell/metadata env)
               :id))
        (keep #{in-gs})
        seq))))                                             ; active?

(defn start-wgs-workload!
  "Use transaction TX to start the WORKLOAD."
  [tx {:keys [cromwell input items output uuid] :as workload}]
  (let [env             (get-cromwell-wgs-environment (all/de-slashify cromwell))
        input           (all/slashify input)
        output          (all/slashify output)
        now             (OffsetDateTime/now)
        workload->label {:workload uuid}]
    (letfn [(submit! [{:keys [id inputs uuid workflow_options] :as workflow}]
              [id (or uuid
                    (if (skip-workflow? env workload workflow)
                      util/uuid-nil
                      (really-submit-one-workflow
                        env
                        (str input (some inputs [:input_bam :input_cram]))
                        output
                        inputs
                        workflow_options
                        workload->label)))])
            (update! [tx [id uuid]]
              (when uuid
                (jdbc/update! tx items
                  {:updated now :uuid uuid}
                  ["id = ?" id])))]
      (let [ids-uuids (map submit! (:workflows workload))]
        (run! (partial update! tx) ids-uuids)
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

;; visible for testing
(defn uses-cromwell-workload-table? [{:keys [version]}]
  (let [[major minor & _] (mapv util/parse-int (str/split version #"\."))]
    (or (< 0 major) (< 3 minor))))

(defmethod workloads/load-workload-impl
  pipeline
  [tx {:keys [items] :as workload}]
  (if-not (uses-cromwell-workload-table? workload)
    (workloads/default-load-workload-impl tx workload)
    (letfn [(unnilify [m] (into {} (filter second m)))
            (unpack-options [m]
              (update m :workflow_options #(when % (util/parse-json %))))]
      (->> (postgres/get-table tx items)
           (mapv (comp unpack-options
                       #(update % :inputs util/parse-json)
                       unnilify))
           (assoc workload :workflows)
           unpack-options
           unnilify))))

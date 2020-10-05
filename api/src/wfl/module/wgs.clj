(ns wfl.module.wgs
  "Reprocess (External) Whole Genomes."
  (:require [clojure.java.io :as io]
            [clojure.data.json :as json]
            [clojure.string :as str]
            [clojure.tools.logging.readable :as logr]
            [wfl.environments :as env]
            [wfl.module.all :as all]
            [wfl.references :as references]
            [wfl.service.cromwell :as cromwell]
            [wfl.service.gcs :as gcs]
            [wfl.service.postgres :as postgres]
            [wfl.jdbc :as jdbc]
            [wfl.util :as util]
            [wfl.wdl :as wdl]
            [wfl.wfl :as wfl])
  (:import [java.time OffsetDateTime]))

(def pipeline "ExternalWholeGenomeReprocessing")

(def workflow-wdl
  "The top-level WDL file and its version."
  {:release "ExternalWholeGenomeReprocessing_v1.1.0"
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

(def fingerprinting
  "Fingerprinting inputs for wgs."
  (let [fp   (str "single_sample/plumbing/bams/20k/NA12878_PLUMBING"
               ".hg38.reference.fingerprint")
        storage "gs://broad-gotc-test-storage/"]
    {:fingerprint_genotypes_file  (str storage fp ".vcf.gz")
     :fingerprint_genotypes_index (str storage fp ".vcf.gz.tbi")}))

(def cram-ref
  "Ref Fasta for CRAM."
  {:cram_ref_fasta        "gs://gcp-public-data--broad-references/hg38/v0/Homo_sapiens_assembly38.fasta"
   :cram_ref_fasta_index  "gs://gcp-public-data--broad-references/hg38/v0/Homo_sapiens_assembly38.fasta.fai"})

(defn make-references
  "HG38 reference, calling interval, and contamination files."
  [prefix]
  (let [hg38 "gs://gcp-public-data--broad-references/hg38/v0/"
        il   "wgs_calling_regions.hg38.interval_list"
        p3   "contamination-resources/1000g/1000g.phase3.100k.b38.vcf.gz.dat"]
    (merge (references/hg38-genome-references prefix)
           {:calling_interval_list       (str hg38 il)
            :contamination_sites_bed     (str hg38 p3 ".bed")
            :contamination_sites_mu      (str hg38 p3 ".mu")
            :contamination_sites_ud      (str hg38 p3 ".UD")})))

(def hack-task-level-values
  "Hack to overload task-level values for wgs pipeline."
  (merge {:wgs_coverage_interval_list
          (str "gs://gcp-public-data--broad-references/"
               "hg38/v0/wgs_coverage_regions.hg38.interval_list")}
         (-> {:disable_sanity_check true}
             (util/prefix-keys :CheckContamination)
             (util/prefix-keys :UnmappedBamToAlignedBam)
             (util/prefix-keys :UnmappedBamToAlignedBam)
             (util/prefix-keys :WholeGenomeGermlineSingleSample)
             (util/prefix-keys :WholeGenomeGermlineSingleSample)
             (util/prefix-keys :WholeGenomeReprocessing)
             (util/prefix-keys :WholeGenomeReprocessing))))

(defn env-inputs
  "Genome inputs for ENVIRONMENT that do not depend on the input file."
  [environment]
  (let [{:keys [google_account_vault_path vault_token_path]}
        (env/stuff environment)]
    {:google_account_vault_path google_account_vault_path
     :vault_token_path vault_token_path
     :unmapped_bam_suffix ".unmapped.bam"
     :papi_settings       {:agg_preemptible_tries 3
                           :preemptible_tries     3}
     :scatter_settings    {:haplotype_scatter_count         10
                           :break_bands_at_multiples_of     100000}}))

(defn make-inputs
  "Return inputs for reprocessing IN-GS into OUT-GS in ENVIRONMENT."
  [environment out-gs in-gs sample]
  (let [[input-key base _] (all/bam-or-cram? in-gs)
        leaf (last (str/split base #"/"))
        [_ out-dir] (gcs/parse-gs-url (util/unsuffix base leaf))
        ref-prefix (get sample :reference_fasta_prefix)
        inputs (-> (zipmap [:base_file_name :final_gvcf_base_name :sample_name]
                           (repeat leaf))
                   (assoc input-key in-gs)
                   (assoc :destination_cloud_path (str out-gs out-dir))
                   (assoc :references (make-references ref-prefix))
                   #_(merge fingerprinting) ;; Uncomment to enable fingerprinting
                   (merge cram-ref)
                   (merge (env-inputs environment)
                          hack-task-level-values))
        output (str (:destination_cloud_path inputs) 
                    (:final_gvcf_base_name sample) 
                    ".cram")]
    (all/throw-when-output-exists-already! output)
    (util/prefix-keys inputs :ExternalWholeGenomeReprocessing)))

(defn active-objects
  "GCS object names of BAMs or CRAMs from IN-GS-URL now active in ENVIRONMENT."
  [environment in-gs-url]
  (prn (format "%s: querying Cromwell in %s" wfl/the-name environment))
  (let [input-keys [:ExternalWholeGenomeReprocessing.input_bam
                    :ExternalWholeGenomeReprocessing.input_cram]
        md (partial cromwell/metadata environment)]
    (letfn [(active? [metadata]
              (let [url (-> metadata :id md :submittedFiles :inputs
                            (json/read-str :key-fn keyword)
                            (some input-keys))]
                (when url
                  (let [[bucket object] (gcs/parse-gs-url url)
                        [_ unsuffixed _] (all/bam-or-cram? object)
                        [in-bucket in-object] (gcs/parse-gs-url in-gs-url)]
                    (when (and (= in-bucket bucket)
                               (str/starts-with? object in-object))
                      unsuffixed)))))]
      (->> {:label  cromwell-label
            :status ["On Hold" "Running" "Submitted"]}
           (cromwell/query environment)
           (keep active?)
           set))))

(defn really-submit-one-workflow
  "Submit IN-GS for reprocessing into OUT-GS in ENVIRONMENT."
  [environment in-gs out-gs sample]
  (let [path (wdl/hack-unpack-resources-hack (:top workflow-wdl))]
    (logr/infof "submitting workflow with: in-gs: %s, out-gs: %s" in-gs out-gs)
    (cromwell/submit-workflow
     environment
     (io/file (:dir path) (path ".wdl"))
     (io/file (:dir path) (path ".zip"))
     (make-inputs environment out-gs in-gs sample)
     (util/make-options environment)
     cromwell-label-map)))

(defn maybe-update-workflow-status!
  "Use transaction TX to update the status of WORKFLOW in ENV."
  [tx env items {:keys [id uuid] :as _workflow}]
  (letfn [(maybe [m k v] (if v (assoc m k v) m))]
    (when uuid
      (let [now    (OffsetDateTime/now)
            status (util/do-or-nil (cromwell/status env uuid))]
        (jdbc/update! tx items
          (maybe {:updated now :uuid uuid} :status status)
          ["id = ?" id])))))

(defn update-workload!
  "Use transaction TX to update _WORKLOAD statuses."
  [tx {:keys [cromwell items] :as _workload}]
  (let [env (@get-cromwell-wgs-environment (all/de-slashify cromwell))]
    (try
      (let [workflows (postgres/get-table tx items)]
        (run! (partial maybe-update-workflow-status! tx env items) workflows))
      (catch Exception cause
        (throw (ex-info "Error updating workload status" {} cause))))))

(defn add-workload!
  "Use transaction TX to add the workload described by BODY."
  [tx {:keys [items] :as body}]
  (let [now          (OffsetDateTime/now)
        [uuid table] (all/add-workload-table! tx workflow-wdl body)]
    (letfn [(idnow [m id] (-> m (assoc :id id) (assoc :updated now)))]
      (jdbc/insert-multi! tx table (map idnow items (rest (range)))))
    {:uuid uuid}))

(defn create-workload
  "Remember the workload specified by BODY."
  [body]
  (jdbc/with-db-transaction [tx (postgres/wfl-db-config)]
    (->> body
      (add-workload! tx)
      (conj ["SELECT * FROM workload WHERE uuid = ?"])
      (jdbc/query tx)
      first
      (filter second)
      (into {}))))

(defn skip-workflow?
  "True when _WORKFLOW in _WORKLOAD in ENV is done or active."
  [env
   {:keys [input output] :as _workload}
   {:keys [input_cram]   :as _workflow}]
  (let [in-gs  (str (all/slashify input)  input_cram)
        out-gs (str (all/slashify output) input_cram)]
    (or (->> out-gs gcs/parse-gs-url
          (apply gcs/list-objects)
          util/do-or-nil seq)        ; done?
      (->> {:label  cromwell-label
            :status ["On Hold" "Running" "Submitted"]}
        (cromwell/query env)
        (map (comp :ExternalWholeGenomeReprocessing.input_cram
               (fn [it] (json/read-str it :key-fn keyword))
               :inputs :submittedFiles
               (partial cromwell/metadata env)
               :id))
        (keep #{in-gs})
        seq))))                    ; active?

(defn start-workload!
  "Use transaction TX to start the WORKLOAD."
  [tx {:keys [cromwell input items output uuid] :as workload}]
  (let [env    (get-cromwell-wgs-environment (all/de-slashify cromwell))
        input  (all/slashify input)
        output (all/slashify output)
        now    (OffsetDateTime/now)]
    (letfn [(maybe [m k v] (if v (assoc m k v) m))
            (submit! [{:keys [id input_cram uuid] :as workflow}]
              [id (or uuid
                    (if (skip-workflow? env workload workflow)
                      util/uuid-nil
                      (really-submit-one-workflow
                        env (str input input_cram) output workflow)))])
            (update! [tx [id uuid]]
              (when uuid
                (jdbc/update! tx items
                  {:updated now :uuid uuid}
                  ["id = ?" id])))]
      (let [workflows (postgres/get-table tx items)
            ids-uuids (map submit! workflows)]
        (jdbc/update! tx :workload {:started now} ["uuid = ?" uuid])
        (run! (partial update! tx) ids-uuids)))))

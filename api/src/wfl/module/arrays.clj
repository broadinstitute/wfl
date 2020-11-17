(ns wfl.module.arrays
  "Process Arrays for the Broad Genomics Platform."
  (:require [clojure.data.json :as json]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [wfl.environments :as env]
            [wfl.api.workloads :as workloads :refer [defoverload]]
            [wfl.jdbc :as jdbc]
            [wfl.module.all :as all]
            [wfl.module.batch :as batch]
            [wfl.references :as references]
            [wfl.service.cromwell :as cromwell]
            [wfl.service.gcs :as gcs]
            [wfl.service.postgres :as postgres]
            [wfl.util :as util]
            [wfl.wdl :as wdl]
            [wfl.wfl :as wfl])
  (:import [java.time OffsetDateTime]))

(def pipeline "GPArrays")

(def workflow-wdl
  "The top-level WDL file and its version."
  {:release "Arrays_v2.3.0"
   :top     "pipelines/broad/arrays/single_sample/Arrays.wdl"})

(def cromwell-label-map
  "The WDL label applied to Cromwell metadata."
  {(keyword wfl/the-name)
   pipeline})

(def primary-keys
  "An arrays workflow can be uniquely identified by its `chip_well_barcode` and
  `analysis_version_number`. Consequently, these are the primary keys in the
  database."
  [:chip_well_barcode :analysis_version_number])

(def fingerprinting
  "Fingerprinting inputs for arrays."
  {:haplotype_database_file "gs://gcp-public-data--broad-references/hg19/v0/Homo_sapiens_assembly19.haplotype_database.txt"
   :variant_rsids_file      "gs://broad-references-private/hg19/v0/Homo_sapiens_assembly19.haplotype_database.snps.list"})

(def other-inputs
  "Miscellaneous inputs for arrays."
  {:contamination_controls_vcf       nil
   :subsampled_metrics_interval_list nil
   :disk_size                        100
   :preemptible_tries                3})

(defn env-inputs
  "Array inputs for ENVIRONMENT that do not depend on the input file."
  [environment]
  {:vault_token_path (get-in env/stuff [environment :vault_token_path])
   :environment      ({:arrays-dev "dev" :arrays-prod "prod"} environment)})

(defn get-per-sample-inputs
  "Throw or return per-sample INPUTS."
  [inputs]
  (let [mandatory-keys [:analysis_version_number
                        :bead_pool_manifest_file
                        :call_rate_threshold
                        :chip_well_barcode
                        :cluster_file
                        :extended_chip_manifest_file
                        :fingerprint_genotypes_vcf_file
                        :fingerprint_genotypes_vcf_index_file
                        :green_idat_cloud_path
                        :params_file
                        :red_idat_cloud_path
                        :reported_gender
                        :sample_alias
                        :sample_lsid]
        optional-keys  [;; genotype concordance inputs
                        :control_sample_vcf_file
                        :control_sample_vcf_index_file
                        :control_sample_intervals_file
                        :control_sample_name
                        :genotype_concordance_threshold
                        ;; cloud path of a thresholds file to be used with zCall
                        :zcall_thresholds_file
                        ;; cloud path of the Illumina gender cluster file
                        :gender_cluster_file
                        ;; arbitrary path to be used by BAFRegress
                        :minor_allele_frequency_file]
        mandatory      (select-keys inputs mandatory-keys)
        optional       (select-keys inputs optional-keys)
        missing        (vec (keep (fn [k] (when (nil? (k mandatory)) k)) mandatory-keys))]
    (when (seq missing)
      (throw (Exception. (format "Missing per-sample inputs: %s" missing))))
    (merge optional mandatory)))

(defn make-inputs
  "Return inputs for arrays processing in ENVIRONMENT from PER-SAMPLE-INPUTS."
  [environment per-sample-inputs]
  (-> (merge references/hg19-arrays-references
             fingerprinting
             other-inputs
             (env-inputs environment)
             (get-per-sample-inputs per-sample-inputs))
      (util/prefix-keys :Arrays)))

;; visible for testing
(def default-options
  {
   :use_relative_output_paths  true
   :read_from_cache            true
   :write_to_cache             true
   :default_runtime_attributes {:zones "us-central1-a us-central1-b us-central1-c us-central1-f"
                                :maxRetries 1}})

(defn make-labels
  "Return labels for arrays pipeline from PER-SAMPLE-INPUTS and OTHER-LABELS."
  [per-sample-inputs other-labels]
  (merge cromwell-label-map
         (select-keys per-sample-inputs primary-keys)
         other-labels))

;; visible for testing
(defn submit-arrays-workflow
  "Submit one workflow to ENVIRONMENT given PER-SAMPLE-INPUTS,
   WORKFLOW-OPTIONS and OTHER-LABELS."
  [environment per-sample-inputs workflow-options other-labels]
  (let [path (wdl/hack-unpack-resources-hack (:top workflow-wdl))]
    (cromwell/submit-workflow
     environment
     (io/file (:dir path) (path ".wdl"))
     (io/file (:dir path) (path ".zip"))
     (make-inputs environment per-sample-inputs)
     workflow-options
     (make-labels per-sample-inputs other-labels))))

(defn ^:private get-cromwell-environment! [{:keys [cromwell]}]
  (let [envs (all/cromwell-environments #{:arrays-dev :arrays-prod} cromwell)]
    (when (not= 1 (count envs))
      (throw (ex-info "no unique environment matching Cromwell URL."
                      {:cromwell     cromwell
                       :environments envs})))
    (first envs)))

;; The table is named with the id generated by the jdbc/insert!
;; so this needs to update the workload table inline after creating the
;; GPArrays table, so far we cannot find a better way to bypass
;; https://www.postgresql.org/docs/current/datatype-numeric.html#DATATYPE-SERIAL
;;
(defn add-arrays-workload!
  "Use transaction TX to add the workload described by REQUEST."
  [tx {:keys [items output] :as request}]
  (gcs/parse-gs-url output)
  (get-cromwell-environment! request)
  (let [[id table] (batch/add-workload-table! tx workflow-wdl request)]
    (letfn [(form [m id] (-> m
                             (update :inputs json/write-str)
                             (assoc :id id)))]
      (jdbc/insert-multi! tx table (map form items (range))))
    id))

(defn start-arrays-workload!
  "Use transaction TX to start the WORKLOAD."
  [tx {:keys [items output uuid] :as workload}]
  (let [now (OffsetDateTime/now)]
    (letfn [(submit! [environment {:keys [id uuid] :as workflow}]
              (let [output-path      (str (all/slashify output) (str/join "/" (mapv workflow primary-keys)))
                    workflow-options (util/deep-merge default-options
                                                      {:final_workflow_outputs_dir output-path})]
                [id (or uuid
                        (submit-arrays-workflow environment (:inputs workflow) workflow-options {:workload uuid}))]))
            (update! [tx [id uuid]]
              (when uuid
                (jdbc/update! tx items
                              {:updated now :uuid uuid :status "Submitted"}
                              ["id = ?" id])))]
      (let [environment       (get-cromwell-environment! workload)
            ids-uuids (map (partial submit! environment) (:workflows workload))]
        (run! (partial update! tx) ids-uuids)
        (jdbc/update! tx :workload {:started now} ["uuid = ?" uuid])))))

(defmethod workloads/create-workload!
  pipeline
  [tx request]
  (->> request
   (add-arrays-workload! tx)
   (workloads/load-workload-for-id tx)))

(defmethod workloads/start-workload!
  pipeline
  [tx {:keys [id] :as workload}]
  (do
    (start-arrays-workload! tx workload)
    (workloads/load-workload-for-id tx id)))

(defmethod workloads/load-workload-impl
  pipeline
  [tx {:keys [items] :as workload}]
  (letfn [(unnilify [m] (into {} (filter second m)))]
    (->> (postgres/get-table tx items)
         (mapv (comp #(update % :inputs util/parse-json)
                     unnilify))
         (assoc workload :workflows)
         unnilify)))
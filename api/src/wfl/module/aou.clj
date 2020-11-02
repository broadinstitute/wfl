(ns wfl.module.aou
  "Process Arrays for the All Of Us project."
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.tools.logging :as log]
            [wfl.environments :as env]
            [wfl.api.workloads :as workloads :refer [defoverload]]
            [wfl.jdbc :as jdbc]
            [wfl.module.all :as all]
            [wfl.references :as references]
            [wfl.service.cromwell :as cromwell]
            [wfl.service.gcs :as gcs]
            [wfl.service.postgres :as postgres]
            [wfl.util :as util]
            [wfl.wdl :as wdl]
            [wfl.wfl :as wfl]
            [clojure.data.json :as json])
  (:import [java.time OffsetDateTime]
           [java.util UUID]
           (java.sql Timestamp)))

(def pipeline "AllOfUsArrays")

(def workflow-wdl
  "The top-level WDL file and its version."
  {:release "Arrays_v2.3.0"
   :top     "pipelines/broad/arrays/single_sample/Arrays.wdl"})

(def cromwell-label-map
  "The WDL label applied to Cromwell metadata."
  {(keyword wfl/the-name)
   pipeline})

(def cromwell-label
  "The WDL label applied to Cromwell metadata."
  (let [[key value] (first cromwell-label-map)]
    (str (name key) ":" value)))

(def fingerprinting
  "Fingerprinting inputs for arrays."
  {:fingerprint_genotypes_vcf_file       nil
   :fingerprint_genotypes_vcf_index_file nil
   :haplotype_database_file              "gs://gcp-public-data--broad-references/hg19/v0/Homo_sapiens_assembly19.haplotype_database.txt"
   :variant_rsids_file                   "gs://broad-references-private/hg19/v0/Homo_sapiens_assembly19.haplotype_database.snps.list"})

(def other-inputs
  "Miscellaneous inputs for arrays."
  {:contamination_controls_vcf       nil
   :subsampled_metrics_interval_list nil
   :disk_size                        100
   :preemptible_tries                3})

(defn map-aou-environment
  "Map AOU-ENV to environment for inputs preparation."
  [aou-env]
  ({:aou-dev "dev" :aou-prod "prod"} aou-env))

(defn env-inputs
  "Array inputs for ENVIRONMENT that do not depend on the input file."
  [environment]
  {:vault_token_path (get-in env/stuff [environment :vault_token_path])
   :environment      (map-aou-environment environment)})

(defn get-per-sample-inputs
  "Throw or return per-sample INPUTS."
  [inputs]
  (let [mandatory-keys [:analysis_version_number
                        :bead_pool_manifest_file
                        :call_rate_threshold
                        :chip_well_barcode
                        :cluster_file
                        :extended_chip_manifest_file
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
  "Return inputs for AoU Arrays processing in ENVIRONMENT from PER-SAMPLE-INPUTS."
  [environment per-sample-inputs]
  (-> (merge references/hg19-arrays-references
        fingerprinting
        other-inputs
        (env-inputs environment)
        (get-per-sample-inputs per-sample-inputs))
    (util/prefix-keys :Arrays)))

;; visible for testing
(def default-options
  {; TODO: add :default_runtime_attributes {:maxRetries 3} here
   :use_relative_output_paths  true
   :read_from_cache            true
   :write_to_cache             true
   :default_runtime_attributes {:zones "us-central1-a us-central1-b us-central1-c us-central1-f"}})

(defn make-labels
  "Return labels for aou arrays pipeline from PER-SAMPLE-INPUTS and OTHER-LABELS."
  [per-sample-inputs other-labels]
  (merge cromwell-label-map
    (select-keys per-sample-inputs [:analysis_version_number :chip_well_barcode])
    other-labels))

;; visible for testing
(defn submit-aou-workflow
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
  (let [envs (all/cromwell-environments #{:aou-dev :aou-prod} cromwell)]
    (when (not= 1 (count envs))
      (throw (ex-info "no unique environment matching Cromwell URL."
               {:cromwell     cromwell
                :environments envs})))
    (first envs)))

;; The table is named with the id generated by the jdbc/insert!
;; so this needs to update the workload table inline after creating the
;; AllOfUsArrays table, so far we cannot find a better way to bypass
;; https://www.postgresql.org/docs/current/datatype-numeric.html#DATATYPE-SERIAL
;;
(defn add-aou-workload!
  "Use transaction TX to add the workload described by BODY to the database DB.
   Due to the continuous nature of the AoU dataflow, this function will only
   create a new workload table if it does not exist otherwise append records
   to the existing one."
  [tx {:keys [creator cromwell pipeline project output] :as request}]
  (gcs/parse-gs-url output)
  (get-cromwell-environment! request)
  (let [{:keys [release top]} workflow-wdl
        {:keys [commit version]} (wfl/get-the-version)
        workloads (jdbc/query tx ["SELECT * FROM workload WHERE project = ? AND pipeline = ?::pipeline AND release = ? AND output = ?"
                                  project pipeline release output])]
    (when (< 1 (count workloads))
      (log/warn "Found more than 1 workloads!")
      (log/error workloads))
    (if-let [workload (first workloads)]
      (:id workload)
      (let [id            (->> {:commit   commit
                                :creator  creator
                                :cromwell cromwell
                                :output   (all/slashify output)
                                :project  project
                                :release  release
                                :uuid     (UUID/randomUUID)
                                :version  version
                                :wdl      top}
                            (jdbc/insert! tx :workload)
                            first
                            :id)
            table         (format "%s_%09d" pipeline id)
            table_seq     (format "%s_id_seq" table)
            kind          (format (str/join " " ["UPDATE workload"
                                                 "SET pipeline = '%s'::pipeline"
                                                 "WHERE id = '%s'"]) pipeline id)
            idx           (format "CREATE SEQUENCE %s AS bigint" table_seq)
            work          (format "CREATE TABLE %s OF %s (PRIMARY KEY (analysis_version_number, chip_well_barcode), id WITH OPTIONS NOT NULL DEFAULT nextval('%s'))" table pipeline table_seq)
            link-idx-work (format "ALTER SEQUENCE %s OWNED BY %s.id" table_seq table)]
        (jdbc/db-do-commands tx [kind idx work link-idx-work])
        (jdbc/update! tx :workload {:items table} ["id = ?" id])
        id))))

(defn start-aou-workload!
  "Use transaction TX to start the WORKLOAD. This is simply updating the
   workload table to mark a workload as 'started' so it becomes append-able."
  [tx {:keys [id] :as workload}]
  (when-not (:started workload)
    (let [now {:started (Timestamp/from (.toInstant (OffsetDateTime/now)))}]
      (jdbc/update! tx :workload now ["id = ?" id])
      (merge workload now))))

(def primary-keys
  "An AoU workflow can be uniquely identified by its `chip_well_barcode` and
  `analysis_version_number`. Consequently, these are the primary keys in the
  database."
  [:chip_well_barcode :analysis_version_number])

(defn- primary-values [sample]
  (mapv sample primary-keys))

(defn ^:private get-existing-samples [tx table samples]
  (letfn [(extract-primary-values [xs]
            (reduce (partial map conj) [#{""} #{-1}] (map primary-values xs)))
          (assemble-query [[barcodes versions]]
            (str/join " "
              ["SELECT chip_well_barcode, analysis_version_number FROM"
               table
               (format "WHERE chip_well_barcode in %s" barcodes)
               (format "AND analysis_version_number in %s" versions)]))]
    (->> samples
      extract-primary-values
      (map util/to-quoted-comma-separated-list)
      assemble-query
      (jdbc/query tx)
      extract-primary-values)))

(defn ^:private remove-existing-samples
  "Retain all `samples` with unique `known-keys`."
  [samples known-keys]
  (letfn [(go [[known-values xs] sample]
              (let [values (primary-values sample)]
                [(map conj known-values values)
                 (if-not (every? identity (map contains? known-values values))
                   (conj xs sample)
                   xs)]))]
    (second (reduce go [known-keys []] samples))))

(defn append-to-workload!
  "Use transaction `tx` to append `notifications` (or samples) to `workload`.
  Note:
  - The `workload` must be `started` in order to be append-able.
  - All samples being appended will be submitted immediately."
  [tx notifications {:keys [uuid items output] :as workload}]
  (when-not (:started workload)
    (throw (Exception. (format "Workload %s is not started yet!" uuid))))
  (letfn [(submit! [environment sample]
            (let [output-path      (str output (str/join "/" (primary-values sample)))
                  workflow-options (util/deep-merge default-options
                                                    {:final_workflow_outputs_dir output-path})]
              (->> (submit-aou-workflow environment sample workflow-options {:workload uuid})
                str ; coerce java.util.UUID -> string
                (assoc (select-keys sample primary-keys)
                  :workflow_options (json/write-str workflow-options)
                  :updated (Timestamp/from (.toInstant (OffsetDateTime/now)))
                  :status "Submitted"
                  :uuid))))]
    (let [environment       (get-cromwell-environment! workload)
          submitted-samples (map (partial submit! environment)
                              (remove-existing-samples notifications
                                (get-existing-samples tx items notifications)))]
      (jdbc/insert-multi! tx items submitted-samples))))

(defmethod workloads/create-workload!
  pipeline
  [tx request]
  (->>
      (add-aou-workload! tx request)
    (workloads/load-workload-for-id tx)))

(defoverload workloads/start-workload! pipeline start-aou-workload!)

;; The arrays module is always "open" for appending workflows - once started,
;; it cannot be stopped!
(defmethod workloads/update-workload!
  pipeline
  [tx workload]
  (try
    (postgres/update-workflow-statuses! tx workload)
    (workloads/load-workload-for-id tx (:id workload))
    (catch Throwable cause
      (throw (ex-info "Error updating aou workload"
               {:workload workload} cause)))))

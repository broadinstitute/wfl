(ns wfl.module.aou
  "Process Arrays for the All Of Us project."
  (:require [clojure.string             :as str]
            [clojure.tools.logging      :as log]
            [wfl.api.workloads          :as workloads :refer [defoverload]]
            [wfl.jdbc                   :as jdbc]
            [wfl.module.batch           :as batch]
            [wfl.references             :as references]
            [wfl.service.cromwell       :as cromwell]
            [wfl.service.google.storage :as gcs]
            [wfl.service.postgres       :as postgres]
            [wfl.util                   :as util]
            [wfl.wfl                    :as wfl])
  (:import [java.sql Timestamp]
           [java.time OffsetDateTime]
           [java.util UUID]))

(def pipeline "AllOfUsArrays")

(def workflow-wdl
  "The top-level WDL file and its version."
  {:release "Arrays_v2.3.0"
   :path    "pipelines/broad/arrays/single_sample/Arrays.wdl"})

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

(def ^:private known-cromwells
  ["https://cromwell-gotc-auth.gotc-dev.broadinstitute.org"
   "https://cromwell-aou.gotc-prod.broadinstitute.org"])

(def ^:private inputs+options
  [{:environment "dev"
    :vault_token_path "gs://broad-dsp-gotc-arrays-dev-tokens/arrayswdl.token"}
   {:environment "prod"
    :vault_token_path "gs://broad-dsp-gotc-arrays-prod-tokens/arrayswdl.token"}])

;; visible for testing
(defn cromwell->inputs+options
  "Map cromwell URL to workflow inputs and options for submitting an AllOfUs Arrays workflow.
  The returned environment string here is just a default, input file may specify override."
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
                        :minor_allele_frequency_file
                        ;; some message-specified environment to override WFL's
                        :environment
                        ;; some message-specified vault token path to override WFL's
                        :vault_token_path]
        mandatory      (select-keys inputs mandatory-keys)
        optional       (select-keys inputs optional-keys)
        missing        (vec (keep (fn [k] (when (nil? (k mandatory)) k)) mandatory-keys))]
    (when (seq missing)
      (throw (Exception. (format "Missing per-sample inputs: %s" missing))))
    (merge optional mandatory)))

(defn make-inputs
  "Return inputs for AoU Arrays processing in Cromwell given URL from PER-SAMPLE-INPUTS."
  [url per-sample-inputs]
  (-> (merge references/hg19-arrays-references
             fingerprinting
             other-inputs
             (cromwell->inputs+options url)
             (get-per-sample-inputs per-sample-inputs))
      (update :environment str/lower-case)
      (util/prefix-keys :Arrays.)))

;; visible for testing
(def default-options
  {:use_relative_output_paths  true
   :read_from_cache            true
   :write_to_cache             true
   :default_runtime_attributes {:zones "us-central1-a us-central1-b us-central1-c us-central1-f"
                                :maxRetries 1}})

(defn make-labels
  "Return labels for aou arrays pipeline from PER-SAMPLE-INPUTS and OTHER-LABELS."
  [per-sample-inputs other-labels]
  (merge cromwell-label-map
         (select-keys per-sample-inputs [:analysis_version_number :chip_well_barcode])
         other-labels))

;; visible for testing
(defn submit-aou-workflow
  "Submit one workflow to Cromwell URL given PER-SAMPLE-INPUTS,
   WORKFLOW-OPTIONS and OTHER-LABELS."
  [url per-sample-inputs workflow-options other-labels]
  (cromwell/submit-workflow
   url
   workflow-wdl
   (make-inputs url per-sample-inputs)
   workflow-options
   (make-labels per-sample-inputs other-labels)))

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
  [tx {:keys [creator executor pipeline project output] :as _request}]
  (gcs/parse-gs-url output)
  (let [slashified-output (util/slashify output)
        {:keys [release path]} workflow-wdl
        {:keys [commit version]} (wfl/get-the-version)
        workloads (jdbc/query tx ["SELECT * FROM workload WHERE project = ? AND pipeline = ?::pipeline AND release = ? AND output = ?"
                                  project pipeline release slashified-output])]
    (when (< 1 (count workloads))
      (log/warn "Found more than 1 workloads!")
      (log/error workloads))
    (if-let [workload (first workloads)]
      (:id workload)
      (let [id            (->> {:commit   commit
                                :creator  creator
                                :executor executor
                                :output   slashified-output
                                :project  project
                                :release  release
                                :uuid     (UUID/randomUUID)
                                :version  version
                                :wdl      path}
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
  (if (:started workload)
    workload
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
  [tx notifications {:keys [uuid items output executor] :as workload}]
  (when-not (:started workload)
    (throw (Exception. (format "Workload %s is not started" uuid))))
  (when (:stopped workload)
    (throw (Exception. (format "Workload %s has been stopped" uuid))))
  (letfn [(submit! [url sample]
            (let [output-path      (str output (str/join "/" (primary-values sample)))
                  workflow-options (util/deep-merge default-options
                                                    {:final_workflow_outputs_dir output-path})]
              (->> (submit-aou-workflow url sample workflow-options {:workload uuid})
                   str ; coerce java.util.UUID -> string
                   (assoc (select-keys sample primary-keys)
                          :updated (Timestamp/from (.toInstant (OffsetDateTime/now)))
                          :status "Submitted"
                          :uuid))))]
    (let [executor       (is-known-cromwell-url? executor)
          submitted-samples (map (partial submit! executor)
                                 (remove-existing-samples notifications
                                                          (get-existing-samples tx items notifications)))]
      (jdbc/insert-multi! tx items submitted-samples)
      submitted-samples)))

(defn ^:private aou-workflows
  [tx {:keys [items] :as _workload}]
  (batch/tag-workflows
   (batch/pre-v0_4_0-deserialize-workflows (postgres/get-table tx items))))

(defn ^:private aou-wokflows-by-status
  [tx {:keys [items] :as _workload} status]
  (batch/tag-workflows
   (batch/pre-v0_4_0-deserialize-workflows
    (batch/query-workflows-with-status tx items status))))

(defmethod workloads/create-workload!
  pipeline
  [tx request]
  (workloads/load-workload-for-id tx (add-aou-workload! tx request)))

(defoverload workloads/start-workload! pipeline start-aou-workload!)
(defoverload workloads/stop-workload!  pipeline batch/stop-workload!)

(defmethod workloads/update-workload!
  pipeline
  [tx {:keys [started finished] :as workload}]
  (letfn [(update! [{:keys [id] :as workload}]
            (batch/update-workflow-statuses! tx workload)
            (when (:stopped workload)
              (batch/update-workload-status! tx workload))
            (workloads/load-workload-for-id tx id))]
    (if (and started (not finished)) (update! workload) workload)))

(defoverload workloads/workflows           pipeline aou-workflows)
(defoverload workloads/workflows-by-status pipeline aou-wokflows-by-status)
(defoverload workloads/retry               pipeline batch/retry-unsupported)
(defoverload workloads/load-workload-impl  pipeline batch/load-batch-workload-impl)
(defoverload workloads/to-edn              pipeline batch/workload-to-edn)

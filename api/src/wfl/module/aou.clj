(ns wfl.module.aou
"Process Arrays for the All Of Us project."
(:require [clojure.java.io :as io]
  [clojure.string :as str]
  [clojure.tools.logging :as log]
  [wfl.environments :as env]
  [wfl.api.workloads :as workloads]
  [wfl.jdbc :as jdbc]
  [wfl.module.all :as all]
  [wfl.references :as references]
  [wfl.service.cromwell :as cromwell]
  [wfl.service.gcs :as gcs]
  [wfl.util :as util]
  [wfl.wdl :as wdl]
  [wfl.wfl :as wfl])
(:import [java.time OffsetDateTime]
  [java.util UUID]))

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
        optional-keys [;; genotype concordance inputs
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
        mandatory  (select-keys inputs mandatory-keys)
        optional   (select-keys inputs optional-keys)
        missing (vec (keep (fn [k] (when (nil? (k mandatory)) k)) mandatory-keys))]
    (when (seq missing)
      (throw (Exception. (format "Missing per-sample inputs: %s" missing))))
    (merge optional mandatory)))

(defn make-inputs
  "Return inputs for AoU Arrays processing in ENVIRONMENT from PER-SAMPLE-INPUTS."
  [environment per-sample-inputs]
  (let [inputs (merge references/hg19-arrays-references
                 fingerprinting
                 other-inputs
                 (env-inputs environment)
                 (get-per-sample-inputs per-sample-inputs))]
    (util/prefix-keys inputs :Arrays)))

(defn make-options
  "Return options for aou arrays pipeline."
  [sample-output-path]
  {; TODO: add :default_runtime_attributes {:maxRetries 3} here
   :final_workflow_outputs_dir sample-output-path
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

(defn active-or-done-objects
  "Query by _PRIMARY-VALS to get a set of active or done objects from Cromwell in ENVIRONMENT."
  [environment {:keys [analysis_version_number chip_well_barcode] :as _primary-vals}]
  (prn (format "%s: querying Cromwell in %s" wfl/the-name environment))
  (let [primary-keys [:analysis_version_number
                      :chip_well_barcode]
        md           (partial cromwell/metadata environment)]
    (letfn [(active? [metadata]
              (let [cromwell-id                       (metadata :id)
                    analysis-version-chip-well-barcode (-> cromwell-id md :inputs
                                                         (select-keys primary-keys))]
                (when analysis-version-chip-well-barcode
                  (let [found-analysis-version-number (:analysis_version_number analysis-version-chip-well-barcode)
                        found-chip-well-barcode       (:chip_well_barcode analysis-version-chip-well-barcode)]
                    (when (and (= found-analysis-version-number analysis_version_number)
                            (= found-chip-well-barcode chip_well_barcode))
                      analysis-version-chip-well-barcode)))))]
      (->> {:label  cromwell-label
            :status ["On Hold" "Running" "Submitted" "Succeeded"]}
        (cromwell/query environment)
        (keep active?)
        (filter seq)
        set))))

(defn really-submit-one-workflow
  "Submit one workflow to ENVIRONMENT given PER-SAMPLE-INPUTS,
   SAMPLE-OUTPUT-PATH and OTHER-LABELS."
  [environment per-sample-inputs sample-output-path other-labels]
  (let [path (wdl/hack-unpack-resources-hack (:top workflow-wdl))]
    (cromwell/submit-workflow
      environment
      (io/file (:dir path) (path ".wdl"))
      (io/file (:dir path) (path ".zip"))
      (make-inputs environment per-sample-inputs)
      (make-options sample-output-path)
      (make-labels per-sample-inputs other-labels))))

#_(defn update-workload!
    "Use transaction TX to update WORKLOAD statuses."
    [tx workload])

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
  [tx {:keys [creator cromwell pipeline project output] :as _body}]
  (gcs/parse-gs-url output)
  (let [{:keys [release top]} workflow-wdl
        {:keys [commit version]} (wfl/get-the-version)
        workloads (jdbc/query tx ["SELECT * FROM workload WHERE project = ? AND pipeline = ?::pipeline AND release = ? AND output = ?"
                                  project pipeline release output])]
    (when-not (<= 1 (count workloads))
      (log/warn "Found more than 1 workloads!")
      (log/error workloads))
    (:id
      (if-let [workload (first workloads)]
        workload
        (let [workloads     (jdbc/insert! tx :workload {:commit   commit
                                                        :creator  creator
                                                        :cromwell cromwell
                                                        :input    "aou-inputs-placeholder"
                                                        :output   (all/de-slashify output)
                                                        :project  project
                                                        :release  release
                                                        :uuid     (UUID/randomUUID)
                                                        :version  version
                                                        :wdl      top})
              {:keys [id uuid]} (first workloads)
              table         (format "%s_%09d" pipeline id)
              table_seq     (format "%s_id_seq" table)
              kind          (format (str/join " " ["UPDATE workload"
                                                   "SET pipeline = '%s'::pipeline"
                                                   "WHERE uuid = '%s'"]) pipeline uuid)
              idx           (format "CREATE SEQUENCE %s AS bigint" table_seq)
              work          (format "CREATE TABLE %s OF %s (PRIMARY KEY (analysis_version_number, chip_well_barcode), id WITH OPTIONS NOT NULL DEFAULT nextval('%s'))" table pipeline table_seq)
              link-idx-work (format "ALTER SEQUENCE %s OWNED BY %s.id" table_seq table)]
          (jdbc/db-do-commands tx [kind idx work link-idx-work])
          (jdbc/update! tx :workload {:items table} ["id = ?" id])
          (first (jdbc/query tx ["SELECT * FROM workload WHERE uuid = ?" uuid])))))))

(defn start-aou-workload!
  "Use transaction TX to start the WORKLOAD by UUID. This is simply updating the
   workload table to mark a workload as 'started' so it becomes append-able."
  [tx {:keys [uuid] :as _workload}]
  (let [result (jdbc/query tx ["SELECT * FROM workload WHERE uuid = ?" uuid])
        started (:started result)]
    (when (nil? started)
      (let [now (OffsetDateTime/now)]
        (jdbc/update! tx :workload {:started now} ["uuid = ?" uuid])))))

(defn keep-primary-keys
  "Only return the primary keys of a SAMPLE."
  [sample]
  (select-keys sample [:chip_well_barcode :analysis_version_number]))

(defn remove-existing-samples!
  "Return set of SAMPLEs that are not registered in workload TABLE using transaction TX."
  [tx samples table]
  (letfn [(q [[left right]] (fn [it] (str left it right)))
          (extract-key-groups [l] [(map :chip_well_barcode l) (map :analysis_version_number l)])
          (make-query-group [v] (->> v (map (q "''")) (str/join ",") ((q "()"))))
          (assemble-query [query l] (format query (first l) (last l)))]
    (let [samples  (map keep-primary-keys samples)
          existing (->> samples
                     (extract-key-groups)
                     (map make-query-group)
                     (assemble-query (str/join " " ["SELECT * FROM" table "WHERE chip_well_barcode in %s"
                                                    "AND analysis_version_number in %s"]))
                     (jdbc/query tx)
                     (map keep-primary-keys)
                     set)]
      (set (remove existing samples)))))

(defn append-to-workload!
  "Use transaction TX to append the samples to a WORKLOAD identified by uuid.
   The workload needs to be marked as 'started' in order to be append-able, and
   any samples being added to the workload table will be submitted right away."
  [tx {:keys [notifications uuid environment] :as _workload}]
  (let [workload           (first (jdbc/query tx ["SELECT * FROM workload WHERE uuid = ?" uuid]))]
    (when-not (:started workload)
      (throw (Exception. (format "Workload %s is not started yet!" uuid))))
    (let [now                         (OffsetDateTime/now)
          environment                 (wfl/throw-or-environment-keyword! environment)
          table                       (:items workload)
          output-bucket               (:output workload)
          primary-keys                (map keep-primary-keys notifications)
          primary-keys-notifications  (zipmap primary-keys notifications)
          new-samples                 (remove-existing-samples! tx primary-keys table)
          to-be-submitted             (->> primary-keys-notifications
                                        (keys)
                                        (keep new-samples)
                                        (select-keys primary-keys-notifications))
          workload->label             {:workload uuid}]
      (letfn [(sql-rize [m] (-> m
                              (assoc :updated now)
                              keep-primary-keys))
              (submit! [sample]
                (let [{:keys [chip_well_barcode analysis_version_number] :as sample-pks} (keep-primary-keys sample)
                      output-path (str/join "/" [output-bucket chip_well_barcode analysis_version_number])]
                  [(really-submit-one-workflow environment sample output-path workload->label) sample-pks]))
              (update! [tx [uuid {:keys [analysis_version_number chip_well_barcode]}]]
                (when uuid
                  (jdbc/update! tx table
                    {:updated now :uuid uuid}
                    ["analysis_version_number = ? AND chip_well_barcode = ?" analysis_version_number
                     chip_well_barcode])
                  {:updated                 (str now)
                   :uuid                    uuid
                   :analysis_version_number analysis_version_number
                   :chip_well_barcode       chip_well_barcode}))]
        (let [submitted-uuids-pks (map submit! (vals to-be-submitted))]
          (jdbc/insert-multi! tx table (map sql-rize (vals to-be-submitted)))
          (doall (map (partial update! tx) submitted-uuids-pks)))))))

(defmethod workloads/create-workload!
  pipeline
  [tx request]
  (->>
    (add-aou-workload! tx request)
    (workloads/load-workload-for-id tx)))

(defmethod workloads/start-workload!
  pipeline
  [tx {:keys [id] :as workload}]
  (do
    (start-aou-workload! tx workload)
    (workloads/load-workload-for-id tx id)))

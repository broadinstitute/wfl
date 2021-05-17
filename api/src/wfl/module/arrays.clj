(ns wfl.module.arrays
  "Process Arrays for the Broad Genomics Platform."
  (:require [clojure.data.json     :as json]
            [clojure.string        :as str]
            [wfl.api.workloads     :as workloads :refer [defoverload]]
            [wfl.jdbc              :as jdbc]
            [wfl.module.batch      :as batch]
            [wfl.references        :as references]
            [wfl.service.firecloud :as firecloud]
            [wfl.service.postgres  :as postgres]
            [wfl.util              :as util])
  (:import [java.time OffsetDateTime]))

(def pipeline "GPArrays")

(def methodconfig-name "Arrays")

(def workflow-wdl
  "The top-level WDL file and its version."
  {:release "Arrays_v2.3.0"
   :path    "pipelines/broad/arrays/single_sample/Arrays.wdl"})

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
  "Map cromwell URL to workflow inputs and options for submitting a GP Arrays workflow.
  The returned environment string here is just a default, input file may specify override."
  [url]
  ((zipmap known-cromwells inputs+options) (util/de-slashify url)))

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
  "Return inputs for arrays processing in Cromwell given URL from PER-SAMPLE-INPUTS."
  [url per-sample-inputs]
  (-> (merge references/hg19-arrays-references
             fingerprinting
             other-inputs
             (cromwell->inputs+options url)
             (get-per-sample-inputs per-sample-inputs))
      (util/prefix-keys :Arrays.)))

;; The table is named with the id generated by the jdbc/insert!
;; so this needs to update the workload table inline after creating the
;; GPArrays table, so far we cannot find a better way to bypass
;; https://www.postgresql.org/docs/current/datatype-numeric.html#DATATYPE-SERIAL
;;
(defn add-arrays-workload!
  "Use transaction TX to add the workload described by REQUEST."
  [tx {:keys [items] :as request}]
  (let [[id table] (batch/add-workload-table! tx workflow-wdl request)]
    (letfn [(form [m id] (-> m
                             (update :inputs json/write-str)
                             (assoc :id id)))]
      (jdbc/insert-multi! tx table (map form items (range))))
    id))

(defn start-arrays-workload!
  "Use transaction TX to start the WORKLOAD."
  [tx {:keys [items project uuid] :as workload}]
  (let [now             (OffsetDateTime/now)
        methodconfig-ns (first (str/split project #"/"))
        methodconfig    (str/join "/" [methodconfig-ns methodconfig-name])]
    (letfn [(submit! [{:keys [id inputs] :as _workflow}]
              (let [entity (mapv inputs [:entity-type :entity-name])]
                [id (firecloud/create-submission project methodconfig entity)]))
            (update! [tx [id {:keys [submissionId]}]]
              (jdbc/update! tx items
                            {:updated now :uuid submissionId :status "Submitted"}
                            ["id = ?" id]))]
      (let [ids-uuids (map submit! (workloads/workflows workload))]
        (run! (partial update! tx) ids-uuids)
        (jdbc/update! tx :workload {:started now} ["uuid = ?" uuid])))))

(def update-terra-workflow-statuses!
  "Use `tx` to update `status` of Terra `workflows` in a `workload`."
  (letfn [(get-terra-status [{:keys [project]} workflow]
            (firecloud/get-workflow-status-by-entity project workflow))]
    (batch/make-update-workflows get-terra-status)))

(defmethod workloads/create-workload!
  pipeline
  [tx request]
  (->> (add-arrays-workload! tx request)
       (workloads/load-workload-for-id tx)))

(defmethod workloads/start-workload!
  pipeline
  [tx {:keys [id] :as workload}]
  (start-arrays-workload! tx workload)
  (workloads/load-workload-for-id tx id))

(defmethod workloads/update-workload!
  pipeline
  [tx {:keys [started finished] :as workload}]
  (letfn [(update! [{:keys [id] :as workload}]
            (update-terra-workflow-statuses! tx workload)
            (batch/update-workload-status! tx workload)
            (workloads/load-workload-for-id tx id))]
    (if (and started (not finished)) (update! workload) workload)))

(defoverload workloads/load-workload-impl pipeline batch/load-batch-workload-impl)
(defoverload workloads/stop-workload! pipeline batch/stop-workload!)
(defoverload workloads/workflows      pipeline batch/workflows)

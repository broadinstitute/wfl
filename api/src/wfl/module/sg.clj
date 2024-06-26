(ns wfl.module.sg
  "Handle Somatic Genomes."
  (:require [clojure.data.json          :as json]
            [clojure.set                :as set]
            [clojure.spec.alpha         :as s]
            [clojure.string             :as str]
            [wfl.api.workloads          :as workloads :refer [defoverload]]
            [wfl.jdbc                   :as jdbc]
            [wfl.log                    :as log]
            [wfl.module.all             :as all]
            [wfl.module.batch           :as batch]
            [wfl.references             :as references]
            [wfl.service.clio           :as clio]
            [wfl.service.cromwell       :as cromwell]
            [wfl.service.google.storage :as gcs]
            [wfl.service.postgres       :as postgres]
            [wfl.util                   :as util]
            [wfl.wfl                    :as wfl])
  (:import [java.time OffsetDateTime]))

(def pipeline "GDCWholeGenomeSomaticSingleSample")

;; specs
(s/def ::workflow-inputs (s/keys :req-un [::all/base_file_name
                                          ::all/contamination_vcf
                                          ::all/contamination_vcf_index
                                          ::all/cram_ref_fasta
                                          ::all/cram_ref_fasta_index
                                          ::all/dbsnp_vcf
                                          ::all/dbsnp_vcf_index
                                          ::all/input_cram]))
(def workflow-wdl
  "The top-level WDL file and its version."
  {:release "GDCWholeGenomeSomaticSingleSample_v1.3.1"
   :path    "pipelines/broad/dna_seq/somatic/single_sample/wgs/gdc_genome/GDCWholeGenomeSomaticSingleSample.wdl"})

(defn ^:private cromwell->strings
  "Map Cromwell URL to its options or throw."
  [url]
  (let [known {"https://cromwell-gotc-auth.gotc-dev.broadinstitute.org"
               {:clio-url       "https://clio.gotc-dev.broadinstitute.org"
                :google_project "broad-gotc-dev"
                :jes_gcs_root   "gs://broad-gotc-dev-cromwell-execution"}
               "https://cromwell-gotc-auth.gotc-prod.broadinstitute.org"
               {:clio-url       "https://clio.gotc-prod.broadinstitute.org"
                :google_project "broad-sg-prod-compute1"
                :jes_gcs_root   "gs://broad-sg-prod-execution1/"}}]
    (or (-> url util/de-slashify known)
        (throw (ex-info "Unknown Cromwell URL provided."
                        {:cromwell        url
                         :known-cromwells (keys known)})))))

(defn ^:private cromwellify-workflow-inputs
  "Ready the `inputs` of `_workflow` for Cromwell."
  [_env {:keys [inputs] :as _workflow}]
  (-> references/gdc-sg-references
      (util/deep-merge inputs)
      (util/prefix-keys (str pipeline "."))))

(defn make-workflow-options             ; visible for testing
  "Workflow options for Cromwell at `url` to write to `output`."
  [url output]
  (let [gcr   "us.gcr.io"
        repo  "broad-gotc-prod"
        image "genomes-in-the-cloud:2.4.3-1564508330"
        {:keys [google_project jes_gcs_root]} (cromwell->strings url)]
    {:backend         "PAPIv2"
     :final_workflow_outputs_dir output
     :google_project  google_project
     :jes_gcs_root    jes_gcs_root
     :read_from_cache true
     :write_to_cache  true
     :default_runtime_attributes
     {:docker     (str/join "/" [gcr repo image])
      :maxRetries 1
      :noAddress  false
      :zones      util/google-cloud-zones}}))

(defn create-sg-workload!
  [tx {:keys [common items] :as request}]
  (letfn [(merge-to-json [shared specific]
            (json/write-str (not-empty (util/deep-merge shared specific))))
          (serialize [item id]
            (-> item
                (assoc :id id)
                (update :options #(merge-to-json (:options common) %))
                (update :inputs  #(merge-to-json (:inputs  common) %))))]
    (let [[id table] (batch/add-workload-table! tx workflow-wdl request)]
      (jdbc/insert-multi! tx table (map serialize items (range)))
      (workloads/load-workload-for-id tx id))))

(defn start-sg-workload!
  [tx {:keys [executor id items output] :as workload}]
  (letfn [(update-record! [{:keys [id] :as workflow}]
            (let [values (select-keys workflow [:uuid :status :updated])]
              (jdbc/update! tx items values ["id = ?" id])))]
    (let [now (OffsetDateTime/now)]
      (run! update-record!
            (batch/submit-workload! workload
                                    (workloads/workflows tx workload)
                                    executor
                                    workflow-wdl
                                    cromwellify-workflow-inputs
                                    {(keyword wfl/the-name) pipeline}
                                    (make-workflow-options executor output)))
      (jdbc/update! tx :workload {:started now} ["id = ?" id]))
    (workloads/load-workload-for-id tx id)))

(defn ^:private clio-bam-record
  "Return `nil` or the most recent `clio` record with `bam`."
  [clio bam]
  (let [records (clio/query-bam clio bam)
        n       (count records)]
    (when (> n 1)
      (log/error "More than 1 Clio BAM record" :bam bam :count n))
    (first records)))

(def ^:private clio-key-no-version
  "The Clio metadata fields in a BAM or CRAM record key without `:version`."
  [:data_type :location :project :sample_alias])

(def ^:private clio-cram-keys
  "The Clio CRAM fields to promote to new BAM records."
  (concat clio-key-no-version [:billing_project
                               :document_status
                               :insert_size_metrics_path
                               :notes
                               :version]))

(defn ^:private clio-cram-record
  "Return the useful part of the `clio` record for `input_cram` or throw."
  [clio input_cram]
  (let [records (clio/query-cram clio {:cram_path input_cram})
        n       (count records)]
    (when (not= 1 n)
      (log/error "Expected 1 Clio record with cram_path"
                 :count n :cram_path input_cram))
    (-> records first (select-keys clio-cram-keys))))

(defn ^:private final_workflow_outputs_dir_hack
  "Do to `file` what Cromwell's `{:final_workflow_outputs_dir output}` does."
  [output file]
  (->> (str/split file #"/")
       (drop 3)
       (cons output)
       (str/join "/")))

#_(defn ^:private log-missing-final-files-for-debugging
    "Log any `final-files` missing from Clio BAM record for debugging."
    [final-files]
    (let [get  (comp gcs/gs-object-url first gcs/list-objects)
          want (->  final-files vals           set)
          have (->> final-files vals (map get) set)
          need (set/difference want have)]
      (when-not (empty? need)
        (log/warn "Need output files for Clio.")
        (log/error {:need need}))))

;; This hack depends on how Clio spells error messages.
;;
(def ^:private clio-force=true-error-message-starts
  "How a Clio force=true error message starts."
  "\"Adding this document will overwrite the following existing metadata:")
(def ^:private clio-force=true-error-message-ends
  "How a Clio force=true error message ends."
  "Use 'force=true' to overwrite the existing data.\"")
(defn ^:private hack-try-increment-version-in-clio-add-bam?
  "True when `exception` suggests that `clio-add-bam` might succeed
  with the version incremented."
  [exception]
  (let [{:keys [body reason-phrase status]} (ex-data exception)]
    (and
     (== 400 status)
     (= "Bad Request" reason-phrase)
     (str/starts-with? body clio-force=true-error-message-starts)
     (str/ends-with?   body clio-force=true-error-message-ends))))

(defn ^:private clio-add-bam
  "Add `bam` to `clio`, and try `again` with maybe a new `:version`.
  Always return the BAM record inserted to Clio (`bam` or `again`)."
  [clio bam]
  (try (clio/add-bam clio bam)
       bam
       (catch Throwable x
         (log/error {:bam bam :x x})
         (let [again (if (hack-try-increment-version-in-clio-add-bam? x)
                       (-> bam (select-keys clio-key-no-version)
                           (->> (clio/query-bam clio)
                                (sort-by :version)
                                last :version inc
                                (assoc bam :version)))
                       bam)]
           (clio/add-bam clio again)
           again))))

(defn ^:private maybe-update-clio-and-write-final-files
  "Maybe update `clio-url` with `final` and write files and `metadata`."
  [clio-url final {:keys [inputs] :as metadata}]
  #_(log-missing-final-files-for-debugging final)
  (or (clio-bam-record clio-url (select-keys final [:bam_path]))
      (let [cram   (clio-cram-record clio-url (:input_cram inputs))
            bam    (-> cram (merge final) (dissoc :contamination))
            contam (:contamination final)
            suffix (last (str/split contam #"/"))
            folder (str (util/unsuffix contam suffix))
            record (clio-add-bam clio-url bam)]
        (-> record
            (json/write-str :escape-slash false)
            (gcs/upload-content (str folder "clio-bam-record.json")))
        (-> metadata
            (json/write-str :escape-slash false)
            (gcs/upload-content (str folder "cromwell-metadata.json"))))))

(defn ^:private register-workflow-in-clio
  "Use `output` as a hint to tell Clio the `workflow` outputs of
  `executor` in `workload`."
  [{:keys [executor labels] :as workload} output {:keys [status] :as workflow}]
  (let [workflow-uuid (:uuid workflow)
        workload-uuid (:uuid workload)]
    (when (= "Succeeded" status)
      (let [finalize (partial final_workflow_outputs_dir_hack output)
            clio-url (-> executor cromwell->strings :clio-url)
            cromwell->clio {:bai                 :bai_path
                            :bam                 :bam_path
                            :contamination       :contamination
                            :insert_size_metrics :insert_size_metrics_path}
            metadata (cromwell/metadata executor workflow-uuid)
            bam (-> metadata :outputs
                    (util/unprefix-keys (str pipeline "."))
                    (set/rename-keys cromwell->clio)
                    (select-keys (vals cromwell->clio)))
            final (zipmap (keys bam) (map finalize (vals bam)))]
        (when (some empty? (vals final))
          (log/error "Bad metadata from executor"
                     :executor executor
                     :labels   labels
                     :metadata metadata
                     :workload workload-uuid))
        (maybe-update-clio-and-write-final-files clio-url final metadata)))))

(defn ^:private register-workload-in-clio
  "Register `workload` outputs from `workflows` with Clio."
  [{:keys [output] :as workload} workflows]
  (run! (partial register-workflow-in-clio workload output) workflows))

(defn update-sg-workload!
  "Batch-update `workload-record` statuses."
  [{:keys [id started finished] :as _workload-record}]
  (jdbc/with-db-transaction [tx (postgres/wfl-db-config)]
    (letfn [(load-workload []
              (workloads/load-workload-for-id tx id))
            (update!       [workload]
              (batch/batch-update-workflow-statuses! tx workload)
              (batch/update-workload-status! tx workload)
              (load-workload))]
      (if (and started (not finished))
        (let [{:keys [finished] :as updated} (update! (load-workload))]
          (when finished
            (register-workload-in-clio updated
                                       (workloads/workflows tx updated)))
          updated)
        (load-workload)))))

(defoverload workloads/create-workload!     pipeline create-sg-workload!)
(defoverload workloads/start-workload!      pipeline start-sg-workload!)
(defoverload workloads/update-workload!     pipeline update-sg-workload!)
(defoverload workloads/stop-workload!       pipeline batch/stop-workload!)
(defoverload workloads/load-workload-impl   pipeline batch/load-batch-workload-impl)
(defoverload workloads/workflows            pipeline batch/workflows)
(defoverload workloads/workflows-by-filters pipeline batch/workflows-by-filters)
(defoverload workloads/retry                pipeline batch/retry-unsupported)
(defoverload workloads/to-edn               pipeline batch/workload-to-edn)

(ns wfl.module.sg
  "Handle Somatic Genomes."
  (:require [clojure.data.json :as json]
            [clojure.set :as set]
            [clojure.string :as str]
            [clojure.tools.logging.readable :as log]
            [wfl.api.workloads :as workloads :refer [defoverload]]
            [wfl.jdbc :as jdbc]
            [wfl.module.batch :as batch]
            [wfl.references :as references]
            [wfl.service.clio :as clio]
            [wfl.service.cromwell :as cromwell]
            [wfl.service.google.storage :as gcs]
            [wfl.service.postgres :as postgres]
            [wfl.util :as util]
            [wfl.wfl :as wfl])
  (:import [java.time OffsetDateTime]))

(def pipeline "GDCWholeGenomeSomaticSingleSample")

(def workflow-wdl
  "The top-level WDL file and its version."
  {:release "d7d1019db51e1a215e31644a26a19ccace25f463"
   :path    (str "beta-pipelines/broad/somatic/single_sample/wgs/"
                 "gdc_genome/GDCWholeGenomeSomaticSingleSample.wdl")})

(defn ^:private options-for-cromwell
  "Map Cromwell URL to its options or throw."
  [url]
  (let [known {"https://cromwell-gotc-auth.gotc-dev.broadinstitute.org"
               {:google_project "broad-exomes-dev1"
                :jes_gcs_root   "gs://broad-gotc-dev-cromwell-execution",}
               "https://cromwell-gotc-auth.gotc-prod.broadinstitute.org"
               {:google_project "broad-sg-prod-compute1"
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
        {:keys [google_project jes_gcs_root]} (options-for-cromwell url)]
    (-> {:backend         "PAPIv2"
         :final_workflow_outputs_dir output
         :google_project  google_project
         :jes_gcs_root    jes_gcs_root
         :read_from_cache true
         :write_to_cache  true
         :default_runtime_attributes
         {:docker     (str/join "/" [gcr repo image])
          :maxRetries 1
          :noAddress  false
          :zones      util/google-cloud-zones}})))

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
                                    executor
                                    workflow-wdl
                                    cromwellify-workflow-inputs
                                    {(keyword wfl/the-name) pipeline}
                                    (make-workflow-options executor output)))
      (jdbc/update! tx :workload {:started now} ["id = ?" id]))
    (workloads/load-workload-for-id tx id)))

(defn ^:private clio-bam-record
  "Return `nil` or the single Clio record with `bam`."
  [bam]
  (let [records (clio/query-bam bam)
        n       (count records)]
    (when (> n 1)
      (log/warn "More than 1 Clio BAM record")
      (log/error {:bam_record bam :count n}))
    (first records)))

(defn ^:private clio-cram-record
  "Return the useful part of the Clio record for `input_cram` or throw."
  [input_cram]
  (let [records (clio/query-cram {:cram_path input_cram})
        n       (count records)]
    (when (not= 1 n)
      (log/warn "Expected 1 Clio record with cram_path")
      (log/error {:count n :cram_path input_cram}))
    (-> records first (select-keys [:billing_project
                                    :data_type
                                    :document_status
                                    :insert_size_metrics_path
                                    :location
                                    :notes
                                    :project
                                    :sample_alias
                                    :version]))))

(defn final_workflow_outputs_dir_hack   ; for testing
  "Do to `file` what `{:final_workflow_outputs_dir output}` does."
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

(defn ^:private register-workflow-in-clio
  "Ensure Clio knows the `workflow` outputs of `executor`."
  [executor output {:keys [status uuid] :as workflow}]
  (when (= "Succeeded" status)
    (let [finalize (partial final_workflow_outputs_dir_hack output)
          cromwell->clio {:bai                 :bai_path
                          :bam                 :bam_path
                          :insert_size_metrics :insert_size_metrics_path}
          {:keys [inputs outputs] :as metadata} (cromwell/metadata executor uuid)
          bam (-> outputs
                  (util/unprefix-keys (str pipeline "."))
                  (set/rename-keys cromwell->clio)
                  (select-keys (vals cromwell->clio)))
          final (zipmap (keys bam) (map finalize (vals bam)))]
      (when (some empty? (vals final))
        (log/warn "Bad metadata from executor")
        (log/error {:executor executor :metadata metadata}))
      #_(log-missing-final-files-for-debugging final)
      (or (clio-bam-record (select-keys final [:bam_path]))
          (let [cram (clio-cram-record (:input_cram inputs))
                bam  (merge cram final)]
            (try (clio/add-bam bam)
                 (catch Throwable x
                   (log/error x "Add BAM to Clio failed" {:bam bam
                                                          :x   x}))))))))

(defn ^:private register-workload-in-clio
  "Use `tx` to register `workload` outputs with Clio."
  [tx {:keys [executor items output uuid] :as workload}]
  (->> items
       (postgres/get-table tx)
       (run! (partial register-workflow-in-clio executor output)))
  workload)

(defn update-sg-workload!
  "Use transaction `tx` to batch-update `workload` statuses."
  [tx {:keys [finished id started] :as workload}]
  (postgres/batch-update-workflow-statuses! tx workload)
  (postgres/update-workload-status! tx workload)
  (let [{:keys [finished] :as result} (workloads/load-workload-for-id tx id)]
    (when finished (register-workload-in-clio tx workload))
    result))

(defoverload workloads/create-workload!   pipeline create-sg-workload!)
(defoverload workloads/start-workload!    pipeline start-sg-workload!)
(defoverload workloads/update-workload!   pipeline update-sg-workload!)
(defoverload workloads/load-workload-impl pipeline
  batch/load-batch-workload-impl)

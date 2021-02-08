(ns wfl.module.sg
  "Handle Somatic Genomes."
  (:require [clojure.data.json :as json]
            [clojure.set :as set]
            [clojure.string :as str]
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
  {:release "948bfc0f4251c349adce4d6a6475b2bb31bbad22"
   :path    (str "beta-pipelines/broad/somatic/single_sample/wgs/"
                 "gdc_genome/GDCWholeGenomeSomaticSingleSample.wdl")})

(defn ^:private options-for-cromwell
  "Map Cromwell URL to its options or throw."
  [url]
  (let [known {"https://cromwell-gotc-auth.gotc-dev.broadinstitute.org"
               {:jes_roots ["gs://broad-gotc-dev-cromwell-execution"],
                :projects  ["broad-exomes-dev1"]}
               "https://cromwell-gotc-auth.gotc-prod.broadinstitute.org"
               {:jes_roots ["gs://broad-realign-short-execution1/"
                            "gs://broad-realign-short-execution2/"
                            "gs://broad-realign-short-execution3/"
                            "gs://broad-realign-short-execution4/"
                            "gs://broad-realign-short-execution5/"
                            "gs://broad-realign-short-execution6/"
                            "gs://broad-realign-short-execution7/"
                            "gs://broad-realign-short-execution8/"
                            "gs://broad-realign-short-execution9/"
                            "gs://broad-realign-short-execution10/"],
                :projects ["broad-realign-execution01"
                           "broad-realign-execution02"
                           "broad-realign-execution03"
                           "broad-realign-execution04"
                           "broad-realign-execution05"]}}]
    (or (-> url util/de-slashify known)
        (throw (ex-info "Unknown Cromwell URL provided."
                        {:cromwell        url
                         :known-cromwells (keys known)})))))

(defn ^:private cromwellify-workflow-inputs
  "Ready the `inputs` of `_workflow` for Cromwell."
  [_env {:keys [inputs] :as _workflow}]
  (-> references/gdc-sg-references
      (util/deep-merge inputs)
      (util/prefix-keys pipeline)))

;; visible for testing
(defn make-workflow-options
  "Workflow options for Cromwell at `url` to write to `output`."
  [url output]
  (let [gcr   "us.gcr.io"
        repo  "broad-gotc-prod"
        image "genomes-in-the-cloud:2.4.3-1564508330"
        {:keys [projects jes_roots]} (options-for-cromwell url)]
    (-> {:backend         "PAPIv2"
         :final_workflow_outputs_dir output
         :google_project  (rand-nth projects)
         :jes_gcs_root    (rand-nth jes_roots)
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

(defn clio-bam-record
  "Return `nil` or the single Clio record with metadata `bam`, or throw."
  [bam]
  (let [records (clio/query-bam bam)
        n       (count records)]
    (when (> n 1)
      (throw (ex-info "More than 1 Clio BAM record"
                      {:bam_record bam :count n})))
    (first records)))

(defn clio-cram-record
  "Return the useful part of the Clio record for `input_cram` or throw."
  [input_cram]
  (let [records (clio/query-cram {:cram_path input_cram})
        n       (count records)]
    (when (not= 1 n)
      (throw (ex-info "Expected 1 Clio record with cram_path"
                      {:cram_path input_cram :count n})))
    (-> records first (select-keys [:billing_project
                                    :data_type
                                    :document_status
                                    :insert_size_metrics_path
                                    :location
                                    :notes
                                    :project
                                    :sample_alias
                                    :version]))))

(defn register-workflow-in-clio
  "Ensure Clio knows the `workflow` outputs of `executor`."
  [executor {:keys [uuid] :as workflow}]
  (let [cromwell->clio {:bai                 :bai_path
                        :bam                 :bam_path
                        :insert_size_metrics :insert_size_metrics_path}
        {:keys [inputs outputs] :as metadata} (cromwell/metadata executor uuid)
        bam (-> outputs
                (util/unprefix-keys (str pipeline "."))
                (set/rename-keys cromwell->clio)
                (select-keys (vals cromwell->clio)))]
    (when (some empty? (vals bam))
      (throw (ex-info "Bad metadata from executor" {:executor executor
                                                    :metadata metadata})))
    (if-let [clio-bam (clio-bam-record (select-keys bam [:bam_path]))]
      clio-bam
      (let [get  (comp gcs/gs-object-url first gcs/list-objects)
            cram (clio-cram-record (:input_cram inputs))
            have (->> bam vals (map get) set)
            want (->  bam vals           set)
            need (set/difference want have)]
        (when-not (empty? need)
          (throw (ex-info "Need these output files:" {:need need})))
        (clio/add-bam (merge cram bam))))))

(defn register-workload-in-clio
  "Use `tx` to register `workload` outputs with Clio."
  [tx {:keys [items output uuid] :as workload}]
  (->> items
       (postgres/get-table tx)
       (run! (partial register-workflow-in-clio output pipeline uuid)))
  workload)

(defn update-sg-workload!
  "Use transaction `tx` to batch-update `workload` statuses."
  [tx {:keys [finished id started] :as workload}]
  (letfn [(update []
            (postgres/batch-update-workflow-statuses! tx workload)
            (postgres/update-workload-status! tx workload)
            (workloads/load-workload-for-id tx id))]
    (cond finished (register-workload-in-clio tx workload)
          started  (update)
          :else    workload)))

(defoverload workloads/create-workload!   pipeline create-sg-workload!)
(defoverload workloads/start-workload!    pipeline start-sg-workload!)
(defoverload workloads/update-workload!   pipeline update-sg-workload!)
(defoverload workloads/load-workload-impl pipeline
  batch/load-batch-workload-impl)

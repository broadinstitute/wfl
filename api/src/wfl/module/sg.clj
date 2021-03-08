(ns wfl.module.sg
  "Handle Somatic Genomes."
  (:require [clojure.data.json :as json]
            [clojure.set :as set]
            [clojure.string :as str]
            [clojure.tools.logging.readable :as log]
            [wfl.api.workloads :as workloads :refer [defoverload]]
            [wfl.auth :as auth]
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
  {:path    (str "beta-pipelines/broad/somatic/single_sample/wgs/"
                 "gdc_genome/GDCWholeGenomeSomaticSingleSample.wdl")
   :release "e4dc2ffe11bf037fb1bda1fbabbb64a7b3f5e127"})

(defn ^:private cromwell->strings
  "Map Cromwell URL to its options or throw."
  [url]
  (let [known {"https://cromwell-gotc-auth.gotc-dev.broadinstitute.org"
               {:clio-url       "https://clio.gotc-dev.broadinstitute.org"
                :google_project "broad-gotc-dev"
                :jes_gcs_root   "gs://broad-gotc-dev-cromwell-execution"}
               "https://cromwell-gotc-auth.gotc-prod.broadinstitute.org"
               {:clio-url       #_"https://clio.gotc-prod.broadinstitute.org"
                ,               "https://clio.gotc-dev.broadinstitute.org"
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
  "Return `nil` or the single `clio` record with `bam`."
  [clio bam]
  (let [records (clio/query-bam clio bam)
        n       (count records)]
    (when (> n 1)
      (log/warn "More than 1 Clio BAM record")
      (log/error {:bam_record bam :count n}))
    (first records)))

(defn ^:private clio-cram-record
  "Return the useful part of the `clio` record for `input_cram` or throw."
  [clio input_cram]
  (let [records (clio/query-cram clio {:cram_path input_cram})
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

(defn ^:private clio-add-bam
  "Add `bam` record to `clio`."
  [clio bam]
  (try (clio/add-bam clio bam)
       (catch Throwable x
         (log/error x "Add BAM to Clio failed" {:bam bam
                                                :x   x}))))

(defn maybe-update-clio-and-write-final-files
  "Maybe update `clio-url` with `final` and write files and `metadata`."
  [clio-url final {:keys [inputs] :as metadata}]
  #_(log-missing-final-files-for-debugging final)
  (or (clio-bam-record clio-url (select-keys final [:bam_path]))
      (let [cram   (clio-cram-record clio-url (:input_cram inputs))
            bam    (-> cram (merge final) (dissoc :contamination))
            contam (:contamination final)
            suffix (last (str/split contam #"/"))
            folder (str (util/unsuffix contam suffix))]
        (clio-add-bam clio-url bam)
        (-> bam
            (json/write-str :escape-slash false)
            (gcs/upload-content (str folder "clio-bam-record.json")))
        (-> metadata
            (json/write-str :escape-slash false)
            (gcs/upload-content (str folder "cromwell-metadata.json"))))))

(defn ^:private register-workflow-in-clio
  "Ensure Clio knows the `workflow` outputs of `executor`."
  [executor output {:keys [status uuid] :as workflow}]
  (when (= "Succeeded" status)
    (let [finalize (partial final_workflow_outputs_dir_hack output)
          clio-url (-> executor cromwell->strings :clio-url)
          cromwell->clio {:bai                 :bai_path
                          :bam                 :bam_path
                          :contamination       :contamination
                          :insert_size_metrics :insert_size_metrics_path}
          {:keys [outputs] :as metadata} (cromwell/metadata executor uuid)
          bam (-> outputs
                  (util/unprefix-keys (str pipeline "."))
                  (set/rename-keys cromwell->clio)
                  (select-keys (vals cromwell->clio)))
          final (zipmap (keys bam) (map finalize (vals bam)))]
      (when (some empty? (vals final))
        (log/warn "Bad metadata from executor")
        (log/error {:executor executor :metadata metadata}))
      (maybe-update-clio-and-write-final-files clio-url final metadata))))

;; visible for testing
(defn register-workload-in-clio
  "Use `tx` to register `_workload` outputs with Clio."
  [{:keys [executor items output] :as _workload} tx]
  (run!
   (partial register-workflow-in-clio executor output)
   (postgres/get-table tx items)))

(defn update-sg-workload!
  "Use transaction `tx` to batch-update `workload` statuses."
  [tx {:keys [started finished] :as workload}]
  (letfn [(update! [{:keys [id] :as workload}]
            (postgres/batch-update-workflow-statuses! tx workload)
            (postgres/update-workload-status! tx workload)
            (doto (workloads/load-workload-for-id tx id)
              (register-workload-in-clio tx)))]
    (if (and started (not finished)) (update! workload) workload)))

(defoverload workloads/create-workload!   pipeline create-sg-workload!)
(defoverload workloads/start-workload!    pipeline start-sg-workload!)
(defoverload workloads/update-workload!   pipeline update-sg-workload!)
(defoverload workloads/stop-workload!     pipeline batch/stop-workload!)
(defoverload workloads/load-workload-impl pipeline
  batch/load-batch-workload-impl)

;; Hacks follow.  Blame tbl.

(defn sample->clio-cram
  "Clio CRAM metadata for SAMPLE."
  [sample]
  (let [translation {:location     "Processing Location"
                     :project      "Project"
                     :sample_alias "Collaborator Sample ID"
                     :version      "Version"}]
    (letfn [(translate [m [k v]] (assoc m k (sample v)))]
      (reduce translate {:data_type "WGS"} translation))))

(defn tsv->crams
  "Translate TSV file to CRAM records from `clio`."
  [clio tsv]
  (map (comp first (partial clio/query-cram clio) sample->clio-cram)
       (util/map-tsv-file tsv)))

(defn cram->inputs
  "Translate Clio `cram` record to SG workflow inputs."
  [cram]
  (let [translation {:base_file_name :sample_alias
                     :input_cram     :cram_path}
        contam (str "gs://gatk-best-practices/somatic-hg38"
                    "/small_exac_common_3.hg38.vcf.gz")
        fasta  (str "gs://gcp-public-data--broad-references/hg38/v0"
                    "/Homo_sapiens_assembly38.fasta")
        dbsnp  (str "gs://gcp-public-data--broad-references/hg38/v0"
                    "/gdc/dbsnp_144.hg38.vcf.gz")
        references {:contamination_vcf       contam
                    :contamination_vcf_index (str contam ".tbi")
                    :cram_ref_fasta          fasta
                    :cram_ref_fasta_index    (str fasta ".fai")
                    :dbsnp_vcf               dbsnp
                    :dbsnp_vcf_index         (str dbsnp ".tbi")}]
    (letfn [(translate [m [k v]] (assoc m k (v cram)))]
      {:inputs  (reduce translate references translation)
       :options {:monitoring_script
                 "gs://broad-gotc-prod-storage/scripts/monitoring_script.sh"}})))

(defn crams->workload
  "Return a workload request to process `crams` with SG pipeline"
  [crams]
  {:executor "https://cromwell-gotc-auth.gotc-prod.broadinstitute.org"
   :output   "gs://broad-prod-somatic-genomes-output"
   :pipeline "GDCWholeGenomeSomaticSingleSample"
   :project  "(Test) tbl/GH-1196-sg-prod-data-reprise"
   :items    (mapv cram->inputs crams)})

(comment
  "export GOOGLE_APPLICATION_CREDENTIALS=/Users/tbl/Broad/wfl/wfl-prod-service-account.json"
  (do
    (def dev  "https://clio.gotc-dev.broadinstitute.org")
    (def prod "https://clio.gotc-prod.broadinstitute.org")
    (def tsv
      "../NCI_EOMI_Ship1_WGS_SeqComplete_94samples_forGDCPipelineTesting.tsv")
    (def crams (tsv->crams prod tsv))
    (def cram (first crams))
    (def raw-workload (crams->workload crams)))
  (util/map-tsv-file tsv)
  (count crams)
  crams
  (def workload
    (let [{:keys [items]} raw-workload
          keep?           #{"EOMI-B21C-NB1-A-1-0-D-A82T-36"
                            "EOMI-B21C-TTP1-A-1-1-D-A82T-36"
                            "EOMI-B2BJ-NB1-A-1-0-D-A82T-36"
                            "EOMI-B2BJ-TTP1-A-1-1-D-A82T-36"}]
      (-> workload :items
          (->> (filter (comp keep? :base_file_name :inputs))
               (assoc workload :items)))))
  (let [file (clojure.java.io/file "workload-request.edn")]
    (with-open [writer (clojure.java.io/writer file)]
      (clojure.pprint/pprint workload writer)))
  (let [file (clojure.java.io/file "workload-request.json")]
    (with-open [writer (clojure.java.io/writer file)]
      (json/write workload writer :escape-slash false)))
  (defn execute
    [workload]
    (let [payload  (json/write-str workload :escape-slash false)
          response (clj-http.client/post
                    (str "http://localhost:3000" "/api/v1/exec")
                    {:headers      (auth/get-auth-header)
                     :content-type :json
                     :accept       :json
                     :body         payload})]
      (util/parse-json (:body response))))
  (execute workload)
  (execute (update-in workload [:items] (comp vector first))))

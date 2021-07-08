(ns wfl.module.covid
  "Manage the Sarscov2IlluminaFull pipeline."
  (:require [wfl.api.workloads :as workloads :refer [defoverload]]
            [clojure.spec.alpha :as s]
            [wfl.executor :as executor]
            [wfl.jdbc :as jdbc]
            [wfl.module.batch :as batch]
            [wfl.sink :as sink]
            [wfl.source :as source]
            [wfl.stage :as stage]
            [wfl.util :as util :refer [utc-now]]
            [wfl.wfl :as wfl]
            [wfl.module.all :as all]
            [wfl.service.postgres :as postgres])
  (:import [java.util UUID]
           [wfl.util UserException])
  (:use [clojure.pprint :as pprint]))

(def pipeline nil)

;; specs
(s/def ::executor (s/keys :req-un [::all/name
                                   ::all/fromSource
                                   ::all/methodConfiguration
                                   ::all/methodConfigurationVersion
                                   ::all/workspace]))
(s/def ::creator util/email-address?)
(s/def ::workload-request (s/keys :req-un [::executor
                                           ::all/project
                                           ::all/sink
                                           ::source/source]
                                  :opt-un [::all/labels
                                           ::all/watchers]))

(s/def ::workload-response (s/keys :req-un [::all/created
                                            ::creator
                                            ::executor
                                            ::all/labels
                                            ::all/sink
                                            ::source/source
                                            ::all/uuid
                                            ::all/version
                                            ::all/watchers]
                                   :opt-un [::all/finished
                                            ::all/started
                                            ::all/stopped
                                            ::all/updated]))
;; Workload
(defn ^:private patch-workload [tx {:keys [id]} colls]
  (jdbc/update! tx :workload colls ["id = ?" id]))

(def ^:private workload-metadata-keys
  [:commit
   :created
   :creator
   :executor
   :finished
   :labels
   :sink
   :source
   :started
   :stopped
   :updated
   :uuid
   :version
   :watchers])

(defn ^:private add-workload-metadata
  "Use `tx` to record the workload metadata in `request` in the workload table
   and return the ID the of the new row."
  [tx {:keys [project] :as request}]
  (letfn [(combine-labels [labels]
            (->> (str "project:" project)
                 (conj labels)
                 set
                 sort
                 vec))]
    (-> (update request :labels combine-labels)
        (select-keys [:creator :watchers :labels :project])
        (merge (select-keys (wfl/get-the-version) [:commit :version]))
        (assoc :executor ""
               :output ""
               :release ""
               :wdl ""
               :uuid (UUID/randomUUID))
        (->> (jdbc/insert! tx :workload) first :id))))

(def ^:private update-workload-query
  "UPDATE workload
   SET    source_type    = ?::source
   ,      source_items   = ?
   ,      executor_type  = ?::executor
   ,      executor_items = ?
   ,      sink_type      = ?::sink
   ,      sink_items     = ?
   WHERE  id = ?")

(defn ^:private create-covid-workload
  [tx {:keys [source executor sink] :as request}]
  (let [[source executor sink] (mapv stage/validate-or-throw [source executor sink])
        id (add-workload-metadata tx request)]
    (jdbc/execute!
      tx
      (concat [update-workload-query]
              (source/create-source! tx id source)
              (executor/create-executor! tx id executor)
              (sink/create-sink! tx id sink)
              [id]))
    (workloads/load-workload-for-id tx id)))

(defn ^:private load-covid-workload-impl [tx {:keys [id] :as workload}]
  (let [src-exc-sink {:source   (source/load-source! tx workload)
                      :executor (executor/load-executor! tx workload)
                      :sink     (sink/load-sink! tx workload)}]
    (as-> workload $
          (select-keys $ workload-metadata-keys)
          (merge $ src-exc-sink)
          (filter second $)
          (into {:type :workload :id id} $))))

(defn ^:private start-covid-workload
  "Start creating and managing workflows from the source."
  [tx {:keys [started] :as workload}]
  (letfn [(start [{:keys [id source] :as workload} now]
            (source/start-source! tx source)
            (patch-workload tx workload {:started now :updated now})
            (workloads/load-workload-for-id tx id))]
    (if-not started (start workload (utc-now)) workload)))

(defn ^:private update-covid-workload
  "Use transaction `tx` to update `workload` statuses."
  [tx {:keys [started finished] :as workload}]
  (letfn [(update! [{:keys [id source executor sink] :as workload} now]
            (-> (source/update-source! source)
                (executor/update-executor! executor)
                (sink/update-sink! sink))
            (patch-workload tx workload {:updated now})
            (when (every? stage/done? [source executor sink])
              (patch-workload tx workload {:finished now}))
            (workloads/load-workload-for-id tx id))]
    (if (and started (not finished)) (update! workload (utc-now)) workload)))

(defn ^:private stop-covid-workload
  "Use transaction `tx` to stop the `workload` looking for new data."
  [tx {:keys [started stopped finished] :as workload}]
  (letfn [(stop! [{:keys [id source] :as workload} now]
            (source/stop-source! tx source)
            (patch-workload tx workload {:stopped now :updated now})
            (when-not (:started workload)
              (patch-workload tx workload {:finished now}))
            (workloads/load-workload-for-id tx id))]
    (when-not started
      (throw (UserException. "Cannot stop workload before it's been started."
                             {:workload workload})))
    (if-not (or stopped finished) (stop! workload (utc-now)) workload)))

(defn ^:private workload-to-edn [workload]
  (-> workload
      (util/select-non-nil-keys workload-metadata-keys)
      (dissoc :pipeline)
      (update :source util/to-edn)
      (update :executor util/to-edn)
      (update :sink util/to-edn)))

(defn retry-workflows
  [workflow workloads]

  ; 1. load the workload


  ; 1. Get the workflows of status X and null retry field
  ;(let [workflows (jdbc/with-db-transaction [tx (postgres/wfl-db-config)]
  ;                                          (executor/executor-workflows-by-status tx {:executor workload} workflow status))]
  ;  (println workflows))

  ; 2. Get the distinct list of snapshots for these workflows

  ; 3. Submit the snapshots. Hold onto the new workflow ids

  ; 4. Update the retry field of the executor details table inserting the new workflow id for the old workflow.

)

(comment
  (jdbc/with-db-transaction [tx (postgres/wfl-db-config)]
    (let [w (workloads/load-workload-for-uuid tx "b8b5bbe6-faad-4d03-ad45-a68f6c860c7b")]
      (pprint w)
      (pprint (util/to-edn w))))


  (let
    [workload {
               :started "2021-07-06T20:22:04Z"
               :watchers [ "wfl-non-prod@broad-gotc-dev.iam.gserviceaccount.com" ]
               :labels [ "hornet:test"
                        "project:wfl-dev/CDC_Viral_Sequencing_ranthony_20210701" ]
               :creator "wfl-non-prod@broad-gotc-dev.iam.gserviceaccount.com"
               :updated "2021-07-07T00:28:00Z"
               :created "2021-07-06T20:16:21Z"
               :source {
                        :snapshots [ "a95f31f7-553e-4e60-9d94-6c594c7e3709" ]
                        :name "TDR Snapshots"}
               :finished "2021-07-07T00:28:00Z"
               :commit "3848aad49ca9201b57deca37a4f797901bb775c5"
               :uuid "b8b5bbe6-faad-4d03-ad45-a68f6c860c7b"
               :executor {
                          :workspace "wfl-dev/CDC_Viral_Sequencing_ranthony_20210701"
                          :methodConfiguration "wfl-dev/sarscov2_illumina_full"
                          :methodConfigurationVersion 2
                          :fromSource "importSnapshot"
                          :name "Terra"}
               :version "0.8.0"
               :sink {
                         :workspace "wfl-dev/CDC_Viral_Sequencing_ranthony_20210701"
                         :entityType "flowcell",
                         :fromOutputs {
                                          :submission_xml "submission_xml"
                                          :assembled_ids "assembled_ids"
                                          :num_failed_assembly "num_failed_assembly"
                                          :ivar_trim_stats_png "ivar_trim_stats_png"
                                          :read_counts_raw "read_counts_raw"
                                          :num_samples "num_samples"
                                          :vadr_outputs "vadr_outputs"
                                          :cleaned_reads_unaligned_bams "cleaned_reads_unaligned_bams",
                                          :demux_commonBarcodes "demux_commonBarcodes"
                                          :submission_zip "submission_zip"
                                          :cleaned_bams_tiny "cleaned_bams_tiny"
                                          :data_tables_out "data_tables_out"
                                          :ntc_rejected_batches "ntc_rejected_batches"
                                          :picard_metrics_alignment "picard_metrics_alignment"
                                          :failed_assembly_ids "failed_assembly_ids"
                                          :ivar_trim_stats_html "ivar_trim_stats_html"
                                          :assembly_stats_tsv "assembly_stats_tsv"
                                          :failed_annotation_ids "failed_annotation_ids"
                                          :run_date "run_date"
                                          :genbank_source_table "genbank_source_table"
                                          :num_read_files "num_read_files"
                                          :ntc_rejected_lanes "ntc_rejected_lanes"
                                          :primer_trimmed_read_count "primer_trimmed_read_count"
                                          :gisaid_fasta "gisaid_fasta"
                                          :num_submittable "num_submittable"
                                          :submit_ready "submit_ready"
                                          :passing_fasta "passing_fasta"
                                          :nextclade_auspice_json "nextclade_auspice_json"
                                          :read_counts_depleted "read_counts_depleted"
                                          :cleaned_bam_uris "cleaned_bam_uris"
                                          :num_assembled "num_assembled"
                                          :max_ntc_bases "max_ntc_bases"
                                          :genbank_fasta "genbank_fasta"
                                          :multiqc_report_cleaned "multiqc_report_cleaned"
                                          :num_failed_annotation "num_failed_annotation"
                                          :meta_by_filename_json "meta_by_filename_json"
                                          :primer_trimmed_read_percent "primer_trimmed_read_percent",
                                          :assembly_stats_final_tsv "assembly_stats_final_tsv"
                                          :demux_metrics "demux_metrics"
                                          :submittable_ids "submittable_ids"
                                          :sra_metadata "sra_metadata"
                                          :spikein_counts "spikein_counts"
                                          :raw_reads_unaligned_bams "raw_reads_unaligned_bams"
                                          :ivar_trim_stats_tsv "ivar_trim_stats_tsv"
                                          :picard_metrics_wgs "picard_metrics_wgs"
                                          :nextclade_all_json "nextclade_all_json"
                                          :multiqc_report_raw "multiqc_report_raw"
                                          :sequencing_reports "sequencing_reports"
                                          :demux_outlierBarcodes "demux_outlierBarcodes"
                                          :nextmeta_tsv "nextmeta_tsv"
                                          :assemblies_fasta "assemblies_fasta"
                                          }
                      :identifier "run_id"
                      :name "Terra Workspace"
                         }
               } ]
    (retry-workflows workload "Failed"))
)

(defoverload workloads/create-workload! pipeline create-covid-workload)
(defoverload workloads/start-workload! pipeline start-covid-workload)
(defoverload workloads/update-workload! pipeline update-covid-workload)
(defoverload workloads/stop-workload! pipeline stop-covid-workload)
(defoverload workloads/retry pipeline batch/retry-unsupported)
(defoverload workloads/load-workload-impl pipeline load-covid-workload-impl)
(defmethod workloads/workflows pipeline
  [tx {:keys [executor] :as _workload}]
  (executor/executor-workflows tx executor))
(defmethod workloads/workflows-by-status pipeline
  [tx {:keys [executor] :as _workload} status]
  (executor/executor-workflows-by-status tx executor status))
(defoverload workloads/to-edn pipeline workload-to-edn)

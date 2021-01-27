(ns wfl.module.sg
  "Handle Somatic Genomes."
  (:require [clojure.data.json :as json]
            [clojure.set :as set]
            [clojure.string :as str]
            [wfl.api.workloads :as workloads]
            [wfl.api.workloads :refer [defoverload]]
            [wfl.jdbc :as jdbc]
            [wfl.module.batch :as batch]
            [wfl.module.wgs :as wgs]
            [wfl.references :as references]
            [wfl.service.clio :as clio]
            [wfl.service.gcs :as gcs]
            [wfl.service.postgres :as postgres]
            [wfl.util :as util]
            [wfl.wfl :as wfl])
  (:import [java.time OffsetDateTime]))

(def pipeline "GDCWholeGenomeSomaticSingleSample")

(def ^:private cromwell-label
  "The WDL label applied to Cromwell metadata."
  {(keyword wfl/the-name) pipeline})

(def workflow-wdl
  "The top-level WDL file and its version."
  {:release "b0e3cfef18fc3c4126b7b835ab2b253599a18904"
   :path    (str "beta-pipelines/broad/somatic/single_sample/wgs/"
                 "gdc_genome/GDCWholeGenomeSomaticSingleSample.wdl")})

(defn ^:private cromwellify-workflow-inputs
  "Ready the `inputs` of `_workflow` for Cromwell."
  [_env {:keys [inputs] :as _workflow}]
  (-> references/gdc-sg-references
      (util/deep-merge inputs)
      (util/prefix-keys pipeline)))

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

;; SG is derivative of WGS and should use the same environment.
;;
(defn start-sg-workload!
  [tx {:keys [id items output] :as workload}]
  (letfn [(update-record! [{:keys [id] :as workflow}]
            (let [values (select-keys workflow [:uuid :status :updated])]
              (jdbc/update! tx items values ["id = ?" id])))]
    (let [now (OffsetDateTime/now)
          env (wgs/get-cromwell-environment workload)
          default-options (util/deep-merge
                           (util/make-options env)
                           {:final_workflow_outputs_dir output
                            #_#_:use_relative_output_paths true})]
      (run! update-record! (batch/submit-workload!
                            workload env workflow-wdl
                            cromwellify-workflow-inputs cromwell-label
                            default-options))
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
    (-> records first (select-keys [:data_type
                                    :document_status
                                    :insert_size_histogram_path
                                    :insert_size_metrics_path
                                    :location
                                    :notes
                                    :project
                                    :regulatory_designation
                                    :sample_alias
                                    :version]))))

(defn clio-workflow-item!
  "Ensure Clio knows the `output` files for `item` of `pipeline`."
  [output pipeline {:keys [inputs uuid] :as item}]
  (let [{:keys [base_file_name input_cram]} (util/parse-json inputs)
        base_file_name (or base_file_name
                           (-> input_cram util/leafname
                               (util/unsuffix ".cram")))
        parts [output pipeline uuid pipeline "execution" base_file_name]
        path  (partial str (str/join "/" parts))
        bam   {:bai_path                   (path ".bai")
               :bam_path                   (path ".bam")
               :insert_size_histogram_path (path ".insert_size_histogram.pdf")
               :insert_size_metrics_path   (path ".insert_size_metrics")}]
    (if-let [bam-record (clio-bam-record (select-keys bam [:bam_path]))]
      bam-record
      (let [cram (clio-cram-record input_cram)
            have (-> "" path gcs/parse-gs-url
                     (->> (apply gcs/list-objects)
                          (map gcs/gs-object-url))
                     set)
            want (-> bam vals set)
            need (set/difference want have)]
        (when-not (empty? need)
          (throw (ex-info "Need these output files:" {:need need})))
        (clio/add-bam (merge cram bam))))))

(defn clio-workload!
  "Use `tx` to register `workload` outputs with Clio."
  [tx {:keys [id items output uuid] :as workload}]
  (->> items
       (postgres/get-table tx)
       (run! (partial clio-workflow-item! output pipeline)))
  workload)

(defn update-sg-workload!
  "Use transaction `tx` to batch-update `workload` statuses."
  [tx {:keys [finished id started] :as workload}]
  (letfn [(update []
            (postgres/batch-update-workflow-statuses! tx workload)
            (postgres/update-workload-status! tx workload)
            (workloads/load-workload-for-id tx id))]
    (cond finished (clio-workload! tx workload)
          started  (update)
          :else    workload)))

(defoverload workloads/create-workload!   pipeline create-sg-workload!)
(defoverload workloads/start-workload!    pipeline start-sg-workload!)
(defoverload workloads/update-workload!   pipeline update-sg-workload!)
(defoverload workloads/load-workload-impl pipeline
  batch/load-batch-workload-impl)

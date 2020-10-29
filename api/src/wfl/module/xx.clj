(ns wfl.module.xx
  "Reprocess External Exomes, whatever they are."
  (:require [clojure.data.json :as json]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.tools.logging.readable :as logr]
            [wfl.api.workloads :refer [defoverload]]
            [wfl.api.workloads :as workloads]
            [wfl.environments :as env]
            [wfl.jdbc :as jdbc]
            [wfl.module.all :as all]
            [wfl.module.batch :as batch]
            [wfl.references :as references]
            [wfl.service.gcs :as gcs]
            [wfl.service.postgres :as postgres]
            [wfl.service.cromwell :as cromwell]
            [wfl.util :as util]
            [wfl.wdl :as wdl]
            [wfl.wfl :as wfl])
  (:import [java.time OffsetDateTime]))

(def pipeline "ExternalExomeReprocessing")

(def ^:private cromwell-labels
  "The WDL label applied to Cromwell metadata."
  {(keyword wfl/the-name) pipeline})

(def workflow-wdl
  "The top-level WDL file and its version."
  {:release "ExternalExomeReprocessing_v2.1.1"
   :top     (str "pipelines/broad/reprocessing/external/exome/"
              "ExternalExomeReprocessing.wdl")})

(def ^:private references-defaults
  (let [hg38 "gs://gcp-public-data--broad-references/hg38/v0/"
        hsa  "Homo_sapiens_assembly38"]
    (merge references/hg38-exome-references
      references/contamination-sites
      {:calling_interval_list (str hg38 "exome_calling_regions.v1.interval_list")
       :haplotype_database_file (str hg38 hsa ".haplotype_database.txt")})))

(def ^:private workflow-defaults
  (let [hg38          "gs://gcp-public-data--broad-references/hg38/v0/"
        hsa           "Homo_sapiens_assembly38"
        bait_set_name "whole_exome_illumina_coding_v1"
        HybSelOligos  (str/join "/" ["HybSelOligos" bait_set_name bait_set_name])
        iv1           (str/join "." [HybSelOligos hsa])]
    {:unmapped_bam_suffix  ".unmapped.bam"
     :cram_ref_fasta       (str hg38 hsa ".fasta")
     :cram_ref_fasta_index (str hg38 hsa ".fasta.fai")
     :bait_set_name        bait_set_name
     :bait_interval_list   (str hg38 iv1 ".baits.interval_list")
     :target_interval_list (str hg38 iv1 ".targets.interval_list")
     :references           references-defaults
     :scatter_settings     {:break_bands_at_multiples_of  0
                            :haplotype_scatter_count     50}
     :papi_settings        {:agg_preemptible_tries 3
                            :preemptible_tries     3}}))

;; visible for testing
(defn get-cromwell-environment [{:keys [cromwell]}]
  (let [envs (all/cromwell-environments #{:xx-dev :xx-prod} cromwell)]
    (when (not= 1 (count envs))
      (throw (ex-info "no unique environment matching Cromwell URL."
               {:cromwell     cromwell
                :environments envs})))
    (first envs)))

(defn ^:private cromwellify-inputs [environment inputs]
  (-> (env/stuff environment)
    (select-keys [:google_account_vault_path :vault_token_path])
    (merge inputs)
    (util/prefix-keys (keyword pipeline))))

;; visible for testing
;; Note: the database stores per-workflow inputs so we need to combine
;; any `common-inputs` with these before we commit them to storage.
(defn make-combined-inputs-to-save [output-url common-inputs inputs]
  (let [sample-name (fn [basename] (first (str/split basename #"\.")))
        [_ path] (gcs/parse-gs-url (some inputs [:input_bam :input_cram]))
        basename    (or (:base_file_name inputs) (util/basename path))]
    (-> (util/deep-merge common-inputs inputs)
      (assoc :base_file_name basename)
      (util/assoc-when util/absent? :sample_name (sample-name basename))
      (util/assoc-when util/absent? :final_gvcf_base_name basename)
      (util/assoc-when util/absent? :destination_cloud_path
        (str (all/slashify output-url) (util/dirname path))))))

;; visible for testing
(defn submit-workload! [{:keys [uuid workflows] :as workload}]
  (let [path                 (wdl/hack-unpack-resources-hack (:top workflow-wdl))
        environment          (get-cromwell-environment workload)
        ;; Batch calls have uniform options, so we must group by discrete options to submit
        workflows-by-options (seq (group-by :workflow_options workflows))]
    (letfn [(update-workflow [workflow cromwell-uuid]
              (assoc workflow :uuid cromwell-uuid
                              :status "Submitted"
                              :updated (OffsetDateTime/now)))
            (submit-workflows-by-options [[options ws]]
              (mapv update-workflow
                    ws
                    (cromwell/submit-workflows
                      environment
                      (io/file (:dir path) (path ".wdl"))
                      (io/file (:dir path) (path ".zip"))
                      (map (comp (partial cromwellify-inputs environment) :inputs) ws)
                      options
                      (merge cromwell-labels {:workload uuid}))))]
      (apply concat (mapv submit-workflows-by-options workflows-by-options)))))

(defn create-xx-workload! [tx {:keys [output common_inputs items] :as request}]
  (letfn [(make-workflow-record [workload-options id item]
            (let [options-string (json/write-str (util/deep-merge workload-options (:workflow_options item)))]
              (->> (make-combined-inputs-to-save output common_inputs (:inputs item))
                   json/write-str
                   (assoc {:id id :workflow_options options-string} :inputs))))]
    (let [default-options (util/make-options (get-cromwell-environment request))
          [id table opts] (batch/add-workload-table! tx workflow-wdl request default-options)]
      (->> (map (partial make-workflow-record opts) (range) items)
        (jdbc/insert-multi! tx table))
      (workloads/load-workload-for-id tx id))))

(defn start-xx-workload! [tx {:keys [items id] :as workload}]
  (if (:started workload)
    workload
    (letfn [(update-record! [{:keys [id] :as workflow}]
              (let [values (select-keys workflow [:uuid :status :updated])]
                (jdbc/update! tx items values ["id = ?" id])))]
      (let [now (OffsetDateTime/now)]
        (run! update-record! (submit-workload! workload))
        (jdbc/update! tx :workload {:started now} ["id = ?" id]))
      (workloads/load-workload-for-id tx id))))

(defmethod workloads/load-workload-impl
  pipeline
  [tx {:keys [items] :as workload}]
  (letfn [(unnilify [m] (into {} (filter second m)))
          (load-inputs! [workflow]
            (util/deep-merge workflow-defaults (util/parse-json workflow)))
          (unpack-options [m]
            (update m :workflow_options #(util/parse-json (or % "{}"))))]
    (->> (postgres/get-table tx items)
         (mapv (comp #(update % :inputs load-inputs!) unnilify unpack-options))
         (assoc workload :workflows)
         unnilify
         unpack-options)))

(defoverload workloads/create-workload! pipeline create-xx-workload!)
(defoverload workloads/start-workload! pipeline start-xx-workload!)

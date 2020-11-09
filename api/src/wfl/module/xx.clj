(ns wfl.module.xx
  "Reprocess External Exomes, whatever they are."
  (:require [clojure.data.json :as json]
            [clojure.java.io :as io]
            [clojure.string :as str]
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
      {:calling_interval_list   (str hg38 "exome_calling_regions.v1.interval_list")
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
     :scatter_settings     {:break_bands_at_multiples_of 0
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

(defn ^:private cromwellify-workflow-inputs [environment {:keys [inputs]}]
  (-> (env/stuff environment)
    (select-keys [:google_account_vault_path :vault_token_path])
    (merge inputs)
    (util/prefix-keys (keyword pipeline))))

;; visible for testing
(defn make-inputs-to-save [output-url inputs]
  (let [sample-name (fn [basename] (first (str/split basename #"\.")))
        [_ path] (gcs/parse-gs-url (some inputs [:input_bam :input_cram]))
        basename    (or (:base_file_name inputs) (util/basename path))]
    (-> inputs
      (assoc :base_file_name basename)
      (util/assoc-when util/absent? :sample_name (sample-name basename))
      (util/assoc-when util/absent? :final_gvcf_base_name basename)
      (util/assoc-when util/absent? :destination_cloud_path
        (str (all/slashify output-url) (util/dirname path))))))

;; visible for testing
(defn submit-workload! [{:keys [uuid workflows] :as workload}]
  (let [path            (wdl/hack-unpack-resources-hack (:top workflow-wdl))
        environment     (get-cromwell-environment workload)
        default-options (util/make-options environment)]
    (letfn [(update-workflow [workflow cromwell-uuid]
              (assoc workflow :uuid cromwell-uuid
                              :status "Submitted"
                              :updated (OffsetDateTime/now)))
            (submit-batch! [[options workflows]]
              (map update-workflow
                workflows
                (cromwell/submit-workflows
                  environment
                  (io/file (:dir path) (path ".wdl"))
                  (io/file (:dir path) (path ".zip"))
                  (map (partial cromwellify-workflow-inputs environment) workflows)
                  (util/deep-merge default-options options)
                  (merge cromwell-labels {:workload uuid}))))]
      ;; Group by discrete options to batch submit
      (mapcat submit-batch! (group-by :options workflows)))))

(defn create-xx-workload!
  [tx {:keys [items output common] :as request}]
  (letfn [(merge-to-json [shared specific]
            (json/write-str (util/deep-merge shared specific)))
          (make-workflow-record [id item]
            (-> item
              (assoc :id id)
              (update :options #(merge-to-json (:options common) %))
              (update :inputs #(merge-to-json (:inputs common)
                                 (make-inputs-to-save output %)))))]
    (let [[id table] (batch/add-workload-table! tx workflow-wdl request)]
      (->> items
        (map make-workflow-record (range))
        (jdbc/insert-multi! tx table))
      (workloads/load-workload-for-id tx id))))

(defn start-xx-workload! [tx {:keys [items id] :as workload}]
  (letfn [(update-record! [{:keys [id] :as workflow}]
            (let [values (select-keys workflow [:uuid :status :updated])]
              (jdbc/update! tx items values ["id = ?" id])))]
    (let [now (OffsetDateTime/now)]
      (run! update-record! (submit-workload! workload))
      (jdbc/update! tx :workload {:started now} ["id = ?" id]))
    (workloads/load-workload-for-id tx id)))

(defmethod workloads/load-workload-impl
  pipeline
  [tx {:keys [items] :as workload}]
  (letfn [(unnilify [m] (into {} (filter second m)))
          (load-inputs [m]
            (update m :inputs
              #(->> % util/parse-json (util/deep-merge workflow-defaults))))
          (load-options [m] (update m :options (fnil util/parse-json "null")))]
    (->> (postgres/get-table tx items)
      (mapv (comp unnilify load-inputs load-options))
      (assoc workload :workflows)
      unnilify)))

(defoverload workloads/create-workload! pipeline create-xx-workload!)
(defoverload workloads/start-workload! pipeline start-xx-workload!)

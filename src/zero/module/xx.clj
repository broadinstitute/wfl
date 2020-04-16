(ns zero.module.xx
  "Reprocess External Exomes, whatever they are."
  (:require [clojure.data.json :as json]
            [clojure.java.io :as io]
            [clojure.set :as set]
            [clojure.string :as str]
            [zero.module.all :as all]
            [zero.references :as references]
            [zero.service.cromwell :as cromwell]
            [zero.service.gcs :as gcs]
            [zero.util :as util]
            [zero.wdl :as wdl]
            [zero.zero :as zero]))

(def description
  "Describe the purpose of this command."
  (let [in    "gs://daly_talkowski_asc_external/"
        out   (str in "picard_out_hg38/")
        title (str (str/capitalize zero/the-name) ":")]
    (-> [""
         "%2$s eXternal eXomes"
         "%2$s %1$s xx reprocesses BAMs or CRAMs to CRAMs."
         ""
         "Usage: %1$s xx <env> <max>"
         "       %1$s xx <env> <in> <out>"
         "       %1$s xx <env> <max> <in> <out>"
         ""
         "Where: <env> is an environment,"
         "       <max> is a non-negative integer,"
         "       and <in> and <out> are GCS urls."
         "       <max> is the maximum number of inputs to process."
         "       <in>  is a GCS url to files ending in '.bam' or '.cram'."
         "       <out> is an output URL for the reprocessed CRAMs."
         ""
         "The 2-argument (<env> <max>) command releases up to <max>"
         "workflows from 'On Hold' to 'Submitted' in the Cromwell"
         "specified by the environment <env>."
         ""
         "The 3-argument command reports on the status of workflows"
         "and the counts of BAMs and CRAMs in the <in> and <out> urls."
         ""
         "The 4-argument command queues up to <max> .bam or .cram files"
         "from the <in> URL 'On Hold' to the Cromwell in environment <env>."
         ""
         (str/join \space ["Example: %1$s xx xx 9" in out])]
        (->> (str/join \newline))
        (format zero/the-name title))))

(def workflow-wdl
  "The top-level WDL file and its version."
  {:release "ExternalExomeReprocessing_v1.1"
   :top "pipelines/reprocessing/external/exome/ExternalExomeReprocessing.wdl"})

(def cromwell-label-map
  "The WDL label applied to Cromwell metadata."
  {(keyword (str zero/the-name "-xx"))
   (wdl/workflow-name (:top workflow-wdl))})

(def cromwell-label
  "The WDL label applied to Cromwell metadata."
  (let [[key value] (first cromwell-label-map)]
    (str (name key) ":" value)))

(def references
  "HG38 reference, calling interval, and contamination files."
  (let [hg38ref "gs://gcp-public-data--broad-references/hg38/v0/"
        contam  "Homo_sapiens_assembly38.contam"
        calling "exome_calling_regions.v1"
        subset (str hg38ref contam "." calling)]
    (merge references/hg38-exome-references
           {:calling_interval_list   (str hg38ref calling ".interval_list")
            :contamination_sites_bed (str subset ".bed")
            :contamination_sites_mu  (str subset ".mu")
            :contamination_sites_ud  (str subset ".UD")})))

(def baits-and-targets
  "Baits and targets inputs."
  (let [private              "gs://broad-references-private/"
        bait_set_name        "whole_exome_illumina_coding_v1"
        interval_list        (str private "HybSelOligos/" bait_set_name "/" bait_set_name ".Homo_sapiens_assembly38")
        target_interval_list (str interval_list ".targets.interval_list")
        bait_interval_list   (str interval_list ".baits.interval_list")
        fasta                "gs://gcp-public-data--broad-references/hg38/v0/Homo_sapiens_assembly38.fasta"]
    {:bait_set_name        bait_set_name
     :bait_interval_list   bait_interval_list
     :target_interval_list target_interval_list
     :cram_ref_fasta       fasta
     :cram_ref_fasta_index (str fasta ".fai")}))

(defn make-inputs
  "Return inputs for reprocessing IN-GS into OUT-GS in ENVIRONMENT."
  [environment out-gs in-gs]
  (let [[input-key base _] (all/bam-or-cram? in-gs)
        leaf                    (last (str/split base #"/"))
        [_ out-dir] (gcs/parse-gs-url (util/unsuffix base leaf))
        inputs (-> (zipmap [:base_file_name :final_gvcf_base_name :sample_name]
                           (repeat leaf))
                   (assoc input-key in-gs)
                   (assoc :destination_cloud_path (str out-gs out-dir))
                   (assoc :references references)
                   (merge (util/exome-inputs environment)
                          util/gatk-docker-inputs
                          util/gc_bias_metrics-inputs
                          baits-and-targets))
        {:keys [destination_cloud_path final_gvcf_base_name]} inputs
        output (str destination_cloud_path final_gvcf_base_name ".cram")]
    (all/throw-when-output-exists-already! output)
    (util/prefix-keys inputs :ExternalExomeReprocessing)))

(defn active-objects
  "GCS object names of BAMs or CRAMs from IN-GS-URL now active in ENVIRONMENT."
  [environment in-gs-url]
  (prn (format "%s: querying Cromwell in %s" zero/the-name environment))
  (let [md (partial cromwell/metadata environment)]
    (letfn [(active? [metadata]
              (let [url (-> metadata :id md :submittedFiles :inputs
                            (json/read-str :key-fn keyword)
                            (some [:ExternalExomeReprocessing.input_bam
                                   :ExternalExomeReprocessing.input_cram]))]
                (when url
                  (let [[bucket object]       (gcs/parse-gs-url url)
                        [_ unsuffixed _]      (all/bam-or-cram? object)
                        [in-bucket in-object] (gcs/parse-gs-url in-gs-url)]
                    (when (and (= in-bucket bucket)
                               (str/starts-with? object in-object))
                      unsuffixed)))))]
      (->> {:label cromwell-label
            :status ["On Hold" "Running" "Submitted"]}
           (cromwell/query environment)
           (keep active?)
           set))))

(defn hold-workflow
  "Hold OBJECT from IN-BUCKET for reprocessing into OUT-GS in
  ENVIRONMENT."
  [environment in-bucket out-gs object]
  (let [path  (wdl/hack-unpack-resources-hack (:top workflow-wdl))
        in-gs (gcs/gs-url in-bucket object)]
    (cromwell/hold-workflow
      environment
      (io/file (:dir path) (path ".wdl"))
      (io/file (:dir path) (path ".zip"))
      (make-inputs environment out-gs in-gs)
      (util/make-options environment)
      cromwell-label-map)
    (prn in-gs)))

(defn on-hold-some-workflows
  "Submit 'On Hold' up to MAX workflows from IN-GS to OUT-GS
  in ENVIRONMENT."
  [environment max-string in-gs out-gs]
  (let [max (util/is-non-negative! max-string)
        bucket (all/readable! in-gs)]
    (letfn [(input? [{:keys [name]}]
              (let [[_ unsuffixed suffix] (all/bam-or-cram? name)]
                (when unsuffixed [unsuffixed suffix])))
            (slashify [url] (if (str/ends-with? url "/") url (str url "/")))]
      (let [done   (set/union (all/processed-crams (slashify out-gs))
                              (active-objects environment in-gs))
            suffix (->> in-gs
                        gcs/parse-gs-url
                        (apply gcs/list-objects)
                        (keep input?)
                        (into {}))
            more   (->> suffix
                        keys
                        (remove done)
                        (take max)
                        (map (fn [base] (str base (suffix base)))))]
        (run! (partial hold-workflow environment bucket out-gs) more)))))

(defn release-some-on-hold-workflows
  "Submit up to MAX-STRING workflows from 'On Hold' in ENVIRONMENT."
  [environment max-string]
  (letfn [(release! [id] (prn (cromwell/release-hold environment id)))]
    (let [max (util/is-non-negative! max-string)]
      (->> {:pagesize max :status "On Hold" :label cromwell-label}
           (cromwell/query environment)
           (map :id)
           (take max)
           (run! release!)))))

(defn run
  "Reprocess the BAM or CRAM files described by ARGS."
  [& args]
  (try
    (let [env (zero/throw-or-environment-keyword! (first args))]
      (apply (case (count args)
               4 on-hold-some-workflows
               3 (partial all/report-status cromwell-label)
               2 release-some-on-hold-workflows
               (throw (IllegalArgumentException.
                        "Must specify 2 to 4 arguments.")))
             env (rest args)))
    (catch Exception x
      (binding [*out* *err*] (println description))
      (throw x))))

(ns zero.module.ukb
  "Reprocess CRAMs for the UK Biobank project (aka White Album)."
  (:require [clojure.data.json :as json]
            [clojure.java.io :as io]
            [clojure.pprint :refer [pprint]]
            [clojure.set :as set]
            [clojure.string :as str]
            [zero.module.all :as all]
            [zero.service.cromwell :as cromwell]
            [zero.service.gcs :as gcs]
            [zero.util :as util]
            [zero.references :as references]
            [zero.wdl :as wdl]
            [zero.zero :as zero]))

(def description
  "Describe the purpose of this command."
  (let [in    "gs://broad-pharma5-ukbb-inputs/"
        out   "gs://broad-pharma5-ukbb-outputs/"
        title (str (str/capitalize zero/the-name) ":")]
    (-> [""
         "%2$s %1$s ukb reprocesses CRAMs in Google Cloud."
         ""
         "Usage: %1$s ukb <env> <max>"
         "       %1$s ukb <env> <in> <out>"
         "       %1$s ukb <env> <max> <in> <out>"
         ""
         "Where: <env> is an environment,"
         "       <max> is a non-negative integer,"
         "       and <in> and <out> are GCS urls."
         "       <max> is the maximum number of CRAMs to process."
         "       <in> is a GCS url to CRAMs ending in '.cram'."
         "       <out> is an output URL for the reprocessed CRAMs."
         ""
         "The 2-argument (<env> <max>) command releases up to <max>"
         "workflows from 'On Hold' to 'Submitted' in the Cromwell"
         "specified by the environment <env>."
         ""
         "The 3-argument command reports on the status of workflows"
         "and the counts of CRAMs in the <in> and <out> urls."
         ""
         "The 4-argument command queues up to <max> CRAMs from the"
         "<in> URL 'On Hold' to the Cromwell in environment <env>."
         ""
         (str/join \space ["Example: %1$s ukb pharma5 9" in out])]
        (->> (str/join \newline))
        (format zero/the-name title))))

(def workflow-wdl
  "The top-level WDL file and its version."
  {:release "8ed294ede7fe8ee7d15013de105b3458eb478339"
   :top (str zero/dsde-pipelines
             "pipelines/dna_seq/white_album/WhiteAlbumExomeReprocessing.wdl")})

(def cromwell-label-map
  "The WDL label applied to Cromwell metadata."
  {(keyword (str zero/the-name "-ukb"))
   (wdl/workflow-name (:top workflow-wdl))})

(def cromwell-label
  "The WDL label applied to Cromwell metadata."
  (let [[key value] (first cromwell-label-map)]
    (str (name key) ":" value)))

(def references
  "HG38 reference, calling interval, and contamination files."
  (let [spike   "gs://broad-references-private/HybSelOligos/xgen_plus_spikein/"
        calling "white_album_exome_calling_regions.v1.interval_list"
        contam  (str spike "Homo_sapiens_assembly38.contam.subset")]
    (merge references/hg38-exome-references
           {:calling_interval_list   (str spike calling)
            :contamination_sites_bed (str contam ".bed")
            :contamination_sites_mu  (str contam ".mu")
            :contamination_sites_ud  (str contam ".UD")})))

(def baits-and-targets
  "Baits and targets inputs."
  (let [private       "gs://broad-references-private/"
        bait_set_name "xgen_plus_spikein"
        hsa38         "Homo_sapiens_assembly38"
        bsn2          (str bait_set_name "/" bait_set_name)
        hso           (str "HybSelOligos" "/" bsn2 "." hsa38 ".")
        hg38_ukbb     (str private "hg38_ukbb/genome")
        interval_list (str private hso "targets.interval_list")]
    {:bait_set_name        bait_set_name
     :bait_interval_list   interval_list
     :target_interval_list interval_list
     :cram_ref_fasta       (str hg38_ukbb ".fa")
     :cram_ref_fasta_index (str hg38_ukbb ".fa.fai")}))

(defn make-inputs
  "Return inputs for reprocessing CRAM-GS into OUT-BUCKET in ENVIRONMENT."
  [environment out-bucket cram-gs]
  (let [base (util/unsuffix cram-gs ".cram")
        leaf (last (str/split base #"/"))
        [_ out-dir] (gcs/parse-gs-url (util/unsuffix base leaf))]
    (-> (zipmap [:base_file_name :final_gvcf_base_name :sample_name]
                (repeat leaf))
        (assoc :input_cram cram-gs)
        (assoc :destination_cloud_path (gcs/gs-url out-bucket out-dir))
        (assoc :references references)
        (merge (util/exome-inputs environment)
               util/gatk-docker-inputs
               util/gc_bias_metrics-inputs
               baits-and-targets)
        (util/prefix-keys :WhiteAlbumExomeReprocessing))))

;; "On Hold" inputs do not have a top-level :inputs key!  So GETTER
;; has to parse the input_cram out of the :submittedFiles whose value
;; is a stringified JSON object, which has to be parsed again into
;; JSON, and then filtered.
;;
(defn active-crams
  "GCS object names of CRAMs from IN-GS-URL now active in ENVIRONMENT."
  [environment in-gs-url]
  (prn (format "%s: querying Cromwell in %s" zero/the-name environment))
  (let [mdata  (partial cromwell/metadata environment)
        inputs (comp :inputs :submittedFiles mdata :id)]
    (letfn [(cram? [metadata]
              (when-let [url (-> metadata inputs
                                 (json/read-str :key-fn keyword)
                                 :WhiteAlbumExomeReprocessing.input_cram)]
                (let [[bucket object] (gcs/parse-gs-url url)
                      [in-bucket in-object] (gcs/parse-gs-url in-gs-url)]
                  (when (and (= in-bucket bucket)
                             (str/starts-with? object in-object)
                             (str/ends-with? object ".cram"))
                    (util/unsuffix object ".cram")))))]
      (->> {:label cromwell-label
            :status ["On Hold" "Running" "Submitted"]}
           (cromwell/query environment)
           (keep cram?)
           set))))

(defn hold-cram
  "Hold CRAM from IN-BUCKET for reprocessing into OUT-BUCKET in
  ENVIRONMENT."
  [environment in-bucket out-bucket cram]
  (let [path    (wdl/hack-unpack-resources-hack (:top workflow-wdl))
        wdl     (io/file (:dir path) (path ".wdl"))
        zip     (io/file (:dir path) (path ".zip"))
        cram-gs (gcs/gs-url in-bucket (str cram ".cram"))]
    (cromwell/hold-workflow environment wdl zip
                            (make-inputs environment out-bucket cram-gs)
                            (util/make-options environment)
                            cromwell-label-map)
    (prn cram-gs)))

(defn distinct-buckets!
  "Throw or return the buckets for IN-GS-URL and OUT-GS-URL."
  [in-gs-url out-gs-url]
  (let [[in-bucket  in-prefix]  (gcs/parse-gs-url in-gs-url)
        [out-bucket out-prefix] (gcs/parse-gs-url out-gs-url)]
    (letfn [(readable? [bucket prefix]
              (let [url (gcs/gs-url bucket prefix)]
                (prn (format "%s: reading: %s" zero/the-name url)))
              (util/do-or-nil (gcs/list-objects bucket prefix)))]
      (when (= in-bucket out-bucket)
        (throw (IllegalArgumentException.
                 (format "%s and %s must be in different GCS buckets"
                         in-gs-url out-gs-url))))
      (when-not (and (readable? in-bucket  in-prefix)
                     (readable? out-bucket out-prefix))
        (throw (IllegalArgumentException.
                 (format "%s and %s must be readable"
                         in-gs-url out-gs-url)))))
    [in-bucket out-bucket]))

(defn on-hold-some-crams
  "Submit up to MAX CRAMs from IN-GS to OUT-GS in ENVIRONMENT
  for workflow processing 'On Hold'."
  [environment max-string in-gs out-gs]
  (let [max (util/is-non-negative! max-string)
        [in-bucket out-bucket] (distinct-buckets! in-gs out-gs)]
    (letfn [(cram? [{:keys [name]}]
              (when (str/ends-with? name ".cram")
                (util/unsuffix name ".cram")))
            (slashify [url] (if (str/ends-with? url "/") url (str url "/")))
            (hold! [cram]
              (hold-cram environment in-bucket out-bucket cram))]
      (let [done (set/union (all/processed-crams (slashify out-gs))
                            (active-crams environment in-gs))]
        (->> in-gs
             gcs/parse-gs-url
             (apply gcs/list-objects)
             (keep cram?)
             (remove done)
             (take max)
             (run! hold!))))))

;; Override :pagesize to avoid overloading Cromwell when dribbling
;; workflows in one at a time.
;;
(defn find-some-crams
  "Find up to MAX CRAMs from 'On Hold' in ENVIRONMENT."
  [environment max]
  (->> {:pagesize max :status "On Hold" :label cromwell-label}
       (cromwell/query environment)
       (map :id)
       (take max)))

(defn release-some-crams
  "Submit up to MAX-STRING CRAMs from 'On Hold' in ENVIRONMENT."
  [environment max-string]
  (letfn [(release! [id] (prn (cromwell/release-hold environment id)))]
    (let [max (util/is-non-negative! max-string)]
      (run! release! (find-some-crams environment max)))))

(defn count-crams
  "Count the CRAMs in IN-GS-URL and OUT-GS-URL."
  [in-gs-url out-gs-url]
  (letfn [(crams [gs-url suffix]
            (prn (format "%s: reading: %s" zero/the-name gs-url))
            [gs-url (->> gs-url
                         gcs/parse-gs-url
                         (apply gcs/list-objects)
                         (filter (fn [o] (str/ends-with? (:name o) suffix)))
                         count)])]
    (let [gs-urls (into (array-map) (map crams
                                         [in-gs-url out-gs-url]
                                         (repeat ".cram")))
          remaining (apply - (map gs-urls [in-gs-url out-gs-url]))]
      (into gs-urls [[:remaining remaining]]))))

(defn workflow-status
  "Get the status of workflows in ENVIRONMENT."
  [environment]
  (prn (format "%s: querying %s Cromwell: %s"
               zero/the-name environment (cromwell/url environment)))
  (cromwell/status-counts environment {:label cromwell-label}))

(defn report-status
  "Report workflow statuses in ENVIRONMENT and CRAM counts."
  [environment in-gs-url out-gs-url]
  (pprint (workflow-status environment))
  (pprint (count-crams in-gs-url out-gs-url)))

(defn run
  "Reprocess the CRAMs described by ARGS."
  [& args]
  (try
    (let [env (zero/throw-or-environment-keyword! (first args))]
      (apply (case (count args)
               4 on-hold-some-crams
               3 report-status
               2 release-some-crams
               (throw (IllegalArgumentException.
                        "Must specify 2 to 4 arguments.")))
             env (rest args)))
    (catch Exception x
      (binding [*out* *err*] (println description))
      (throw x))))

(comment
  (cromwell/release-workflows-using-agent
    (fn [] (cons :gotc-dev (find-some-crams :gotc-dev 1))))
  )

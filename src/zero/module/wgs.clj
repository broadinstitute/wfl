(ns zero.module.wgs
  "Reprocess (External) Whole Genomes, whatever they are."
  (:require [clojure.java.io :as io]
            [clojure.data.json :as json]
            [clojure.java.jdbc :as jdbc]
            [clojure.set :as set]
            [clojure.string :as str]
            [zero.environments :as env]
            [zero.module.all :as all]
            [zero.references :as references]
            [zero.service.cromwell :as cromwell]
            [zero.service.postgres :as postgres]
            [zero.service.gcs :as gcs]
            [zero.util :as util]
            [zero.wdl :as wdl]
            [zero.zero :as zero])
  (:import [java.time OffsetDateTime]
           [java.util UUID]))

(def description
  "Describe the purpose of this command."
  (let [in  (str "gs://broad-gotc-test-storage/single_sample/plumbing"
                 "/truth/develop/20k/")
        out "gs://broad-gotc-dev-zero-test/wgs"
        title (str (str/capitalize zero/the-name) ":")]
    (-> [""
         "%2$s Reprocess Genomes"
         "%2$s %1$s wgs reprocesses CRAMs."
         ""
         "Usage: %1$s wgs <env> <in> <out>"
         "       %1$s wgs <env> <max> <in> <out>"
         ""
         "Where: <env> is an environment,"
         "       <max> is a non-negative integer,"
         "       and <in> and <out> are GCS urls."
         "       <max> is the maximum number of inputs to process."
         "       <in>  is a GCS url to files ending in '.bam' or '.cram'."
         "       <out> is an output URL for the reprocessed CRAMs."
         ""
         "The 3-argument command reports on the status of workflows"
         "and the counts of BAMs and CRAMs in the <in> and <out> urls."
         ""
         "The 4-argument command submits up to <max> .bam or .cram files"
         "from the <in> URL to the Cromwell in environment <env>."
         ""
         (str/join \space ["Example: %1$s wgs wgs-dev 42" in out])]
        (->> (str/join \newline))
        (format zero/the-name title))))

(def workflow-wdl
  "The top-level WDL file and its version."
  {:release "WholeGenomeReprocessing_v1.2"
   :top     "pipelines/reprocessing/wgs/WholeGenomeReprocessing.wdl"})

(def adapter-workflow-wdl
  "The adapter WDL file."
  "wdl/ExternalWholeGenomeReprocessing.wdl")

(def cromwell-label-map
  "The WDL label applied to Cromwell metadata."
  {(keyword (str zero/the-name "-wgs"))
   (wdl/workflow-name adapter-workflow-wdl)})

(def cromwell-label
  "The WDL label applied to Cromwell metadata."
  (let [[key value] (first cromwell-label-map)]
    (str (name key) ":" value)))

(def per-sample
  "The per-sample stuff for wgs."
  (let [fp   (str "single_sample/plumbing/bams/20k/NA12878_PLUMBING"
                  ".hg38.reference.fingerprint")
        storage "gs://broad-gotc-test-storage/"]
    {:fingerprint_genotypes_file  (str storage fp ".vcf.gz")
     :fingerprint_genotypes_index (str storage fp ".vcf.gz.tbi")}))

(def references
  "HG38 reference, calling interval, and contamination files."
  (let [hg38 "gs://gcp-public-data--broad-references/hg38/v0/"
        il   "wgs_calling_regions.hg38.interval_list"
        p3   "contamination-resources/1000g/1000g.phase3.100k.b38.vcf.gz.dat"]
    (merge references/hg38-genome-references
           per-sample
           {:calling_interval_list       (str hg38 il)
            :contamination_sites_bed     (str hg38 p3 ".bed")
            :contamination_sites_mu      (str hg38 p3 ".mu")
            :contamination_sites_ud      (str hg38 p3 ".UD")})))

;; Check https://github.com/broadinstitute/dsde-pipelines/blob/8a9a8a3cf9b66a38c7f1b4aaaa67bd9652e42650/tasks/UnmappedBamToAlignedBam.wdl#L149
;;
(def hack-task-level-values
  "Hack to overload task-level values for wgs pipeline."
  (merge {:wgs_coverage_interval_list
          (str "gs://gcp-public-data--broad-references/"
               "hg38/v0/wgs_coverage_regions.hg38.interval_list")}
         ;; uncomment to enable CheckFingerprint
         #_(-> {:haplotype_database_file
                (str "gs://broad-references-private/hg38/v0/"
                     "Homo_sapiens_assembly38.haplotype_database.txt")}
               (util/prefix-keys :WholeGenomeGermlineSingleSample)
               (util/prefix-keys :WholeGenomeReprocessing)
               (util/prefix-keys :WholeGenomeReprocessing))
         (-> {:disable_sanity_check true}
             (util/prefix-keys :CheckContamination)
             (util/prefix-keys :UnmappedBamToAlignedBam)
             (util/prefix-keys :UnmappedBamToAlignedBam)
             (util/prefix-keys :WholeGenomeGermlineSingleSample)
             (util/prefix-keys :WholeGenomeGermlineSingleSample)
             (util/prefix-keys :WholeGenomeReprocessing)
             (util/prefix-keys :WholeGenomeReprocessing))))

(defn genome-inputs
  "Genome inputs for ENVIRONMENT that do not depend on the input file."
  [environment]
  {:google_account_vault_path
   (get-in env/stuff [environment :vault_path_to_picard_account])
   :vault_token_path (get-in env/stuff [environment :vault_token_path])
   :unmapped_bam_suffix ".unmapped.bam"
   :papi_settings       {:agg_preemptible_tries 3
                         :preemptible_tries     3}})

(defn make-inputs
  "Return inputs for reprocessing IN-GS into OUT-GS in ENVIRONMENT."
  [environment out-gs in-gs]
  (let [[input-key base _] (all/bam-or-cram? in-gs)
        leaf (last (str/split base #"/"))
        [_ out-dir] (gcs/parse-gs-url (util/unsuffix base leaf))
        inputs (-> (zipmap [:base_file_name :final_gvcf_base_name :sample_name]
                           (repeat leaf))
                   (assoc input-key in-gs)
                   (assoc :destination_cloud_path (str out-gs out-dir))
                   (assoc :references references)
                   (merge (genome-inputs environment)
                          hack-task-level-values))
        {:keys [destination_cloud_path final_gvcf_base_name]} inputs
        output (str destination_cloud_path final_gvcf_base_name ".cram")]
    (all/throw-when-output-exists-already! output)
    (util/prefix-keys inputs :ExternalWholeGenomeReprocessing)))

(defn active-objects
  "GCS object names of BAMs or CRAMs from IN-GS-URL now active in ENVIRONMENT."
  [environment in-gs-url]
  (prn (format "%s: querying Cromwell in %s" zero/the-name environment))
  (let [input-keys [:ExternalWholeGenomeReprocessing.input_bam
                    :ExternalWholeGenomeReprocessing.input_cram]
        md (partial cromwell/metadata environment)]
    (letfn [(active? [metadata]
              (let [url (-> metadata :id md :submittedFiles :inputs
                            (json/read-str :key-fn keyword)
                            (some input-keys))]
                (when url
                  (let [[bucket object] (gcs/parse-gs-url url)
                        [_ unsuffixed _] (all/bam-or-cram? object)
                        [in-bucket in-object] (gcs/parse-gs-url in-gs-url)]
                    (when (and (= in-bucket bucket)
                               (str/starts-with? object in-object))
                      unsuffixed)))))]
      (->> {:label  cromwell-label
            :status ["On Hold" "Running" "Submitted"]}
           (cromwell/query environment)
           (keep active?)
           set))))

(defn submit-workflow
  "Submit OBJECT from IN-BUCKET for reprocessing into OUT-GS in
  ENVIRONMENT."
  [environment in-bucket out-gs object]
  (let [path  (wdl/hack-unpack-resources-hack adapter-workflow-wdl)
        in-gs (gcs/gs-url in-bucket object)]
    (let [workflow-id (cromwell/submit-workflow
                        environment
                        (io/file (:dir path) (path ".wdl"))
                        (io/file (:dir path) (path ".zip"))
                        (make-inputs environment out-gs in-gs)
                        (util/make-options environment)
                        cromwell-label-map)]
      (prn workflow-id)
      workflow-id)))

(defn submit-some-workflows
  "Submit up to MAX workflows from IN-GS to OUT-GS in ENVIRONMENT."
  [environment max-string in-gs out-gs]
  (let [max    (util/is-non-negative! max-string)
        bucket (all/readable! in-gs)]
    (letfn [(input? [{:keys [name]}]
              (let [[_ unsuffixed suffix] (all/bam-or-cram? name)]
                (when unsuffixed [unsuffixed suffix])))
            (slashify [url] (if (str/ends-with? url "/") url (str url "/")))]
      (let [slashified (slashify out-gs)
            done   (set/union (all/processed-crams slashified)
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
        (mapv (partial submit-workflow environment bucket slashified) more)))))

(defn run
  "Reprocess the BAM or CRAM files described by ARGS."
  [& args]
  (try
    (let [env (zero/throw-or-environment-keyword! (first args))]
      (apply (case (count args)
               4 submit-some-workflows
               3 (partial all/report-status cromwell-label)
               (throw (IllegalArgumentException.
                        "Must specify 3 or 4 arguments.")))
             env (rest args)))
    (catch Exception x
      (binding [*out* *err*] (println description))
      (throw x))))

(defn add-wgs-workload!
  "Add the WGS workload described by BODY to the database DB."
  [db {:keys [creator cromwell input output pipeline project] :as body}]
  (jdbc/with-db-transaction [db db]
    (let [{:keys [commit version]} (zero/get-the-version)
          [{:keys [id]}] (jdbc/insert!
                           db :workload {:commit   commit
                                         :creator  creator
                                         :cromwell cromwell
                                         :input    input
                                         :output   output
                                         :project  project
                                         :release  (:release workflow-wdl)
                                         :uuid     (UUID/randomUUID)
                                         :version  version
                                         :wdl      (:top workflow-wdl)})
          work  (format "%s_%09d" pipeline id)
          kind  (format (str/join " " ["UPDATE workload"
                                       "SET pipeline = '%s'::pipeline"
                                       "WHERE id = %s"]) pipeline id)
          table (format "CREATE TABLE %s OF %s (PRIMARY KEY (id))"
                        work pipeline)
          now   (OffsetDateTime/now)
          load  (map (fn [m id] (-> m
                                    (assoc :id id)
                                    (assoc :updated now)))
                     (:load body) (rest (range)))]
      (jdbc/update! db :workload {:load work} ["id = ?" id])
      (jdbc/db-do-commands db [kind table])
      (jdbc/insert-multi! db work load))))

(defn create-workload
  "Remember the WGS workflow specified by REQUEST."
  [{:keys [body] :as request}]
  (let [environment (keyword (util/getenv "ENVIRONMENT" "debug"))]
    (add-wgs-workload! (postgres/zero-db-config environment) body)))

(comment
  (def body
    {:creator "tbl@broadinstitute.org"
     :cromwell "https://cromwell.gotc-dev.broadinstitute.org"
     :input    "gs://broad-gotc-test-storage/single_sample/plumbing/truth"
     :output   "gs://broad-gotc-dev-zero-test/wgs-test-output"
     :pipeline "ExternalWholeGenomeReprocessing"
     :project  "Testing with tbl"
     :load     [{"unmapped_bam_suffix"  ".unmapped.bam",
                 "sample_name"          "NA12878 PLUMBING",
                 "base_file_name"       "NA12878_PLUMBING",
                 "final_gvcf_base_name" "NA12878_PLUMBING",
                 "input_cram"           "develop/20k/NA12878_PLUMBING.cram"}]})
  (create-workload {:body body})
  )

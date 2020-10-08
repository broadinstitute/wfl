(ns wfl.module.xx
  "Reprocess External Exomes, whatever they are."
  (:require [clojure.data.json :as json]
            [clojure.string :as str]
            [wfl.module.all :as all]
            [wfl.service.gcs :as gcs]
            [wfl.util :as util]
            [wfl.jdbc :as jdbc]
            [wfl.service.postgres :as postgres]
            [clojure.data :as data]
            [wfl.environments :as env])
  (:import [java.time OffsetDateTime]
           (java.util UUID)))

(def pipeline "ExternalExomeReprocessing")

(def workflow-wdl
  "The top-level WDL file and its version."
  {:release "ExternalExomeReprocessing_v2.0.2"
   :top     "pipelines/reprocessing/external/exome/ExternalExomeReprocessing.wdl"})

(def reference-fasta-defaults
  {:ref_pac         "gs://gcp-public-data--broad-references/hg38/v0/Homo_sapiens_assembly38.fasta.64.pac"
   :ref_bwt         "gs://gcp-public-data--broad-references/hg38/v0/Homo_sapiens_assembly38.fasta.64.bwt"
   :ref_dict        "gs://gcp-public-data--broad-references/hg38/v0/Homo_sapiens_assembly38.dict"
   :ref_ann         "gs://gcp-public-data--broad-references/hg38/v0/Homo_sapiens_assembly38.fasta.64.ann"
   :ref_fasta_index "gs://gcp-public-data--broad-references/hg38/v0/Homo_sapiens_assembly38.fasta.fai"
   :ref_alt         "gs://gcp-public-data--broad-references/hg38/v0/Homo_sapiens_assembly38.fasta.64.alt"
   :ref_fasta       "gs://gcp-public-data--broad-references/hg38/v0/Homo_sapiens_assembly38.fasta"
   :ref_sa          "gs://gcp-public-data--broad-references/hg38/v0/Homo_sapiens_assembly38.fasta.64.sa"
   :ref_amb         "gs://gcp-public-data--broad-references/hg38/v0/Homo_sapiens_assembly38.fasta.64.amb"})

(def references-defaults
  {:calling_interval_list    "gs://gcp-public-data--broad-references/hg38/v0/exome_calling_regions.v1.interval_list"
   :contamination_sites_bed  "gs://gcp-public-data--broad-references/hg38/v0/contamination-resources/1000g/1000g.phase3.100k.b38.vcf.gz.dat.bed"
   :contamination_sites_mu   "gs://gcp-public-data--broad-references/hg38/v0/contamination-resources/1000g/1000g.phase3.100k.b38.vcf.gz.dat.mu"
   :contamination_sites_ud   "gs://gcp-public-data--broad-references/hg38/v0/contamination-resources/1000g/1000g.phase3.100k.b38.vcf.gz.dat.UD"
   :dbsnp_vcf                "gs://gcp-public-data--broad-references/hg38/v0/Homo_sapiens_assembly38.dbsnp138.vcf"
   :dbsnp_vcf_index          "gs://gcp-public-data--broad-references/hg38/v0/Homo_sapiens_assembly38.dbsnp138.vcf.idx"
   :evaluation_interval_list "gs://gcp-public-data--broad-references/hg38/v0/exome_evaluation_regions.v1.interval_list"
   :haplotype_database_file  "gs://gcp-public-data--broad-references/hg38/v0/Homo_sapiens_assembly38.haplotype_database.txt"
   :known_indels_sites_vcfs
                             ["gs://gcp-public-data--broad-references/hg38/v0/Mills_and_1000G_gold_standard.indels.hg38.vcf.gz"
                              "gs://gcp-public-data--broad-references/hg38/v0/Homo_sapiens_assembly38.known_indels.vcf.gz"]
   :known_indels_sites_indices
                             ["gs://gcp-public-data--broad-references/hg38/v0/Mills_and_1000G_gold_standard.indels.hg38.vcf.gz.tbi"
                              "gs://gcp-public-data--broad-references/hg38/v0/Homo_sapiens_assembly38.known_indels.vcf.gz.tbi"]
   :reference_fasta          reference-fasta-defaults})

(def scatter-settings-defaults
  {:haplotype_scatter_count     50
   :break_bands_at_multiples_of 0})

(def papi-settings-defaults
  {:agg_preemptible_tries 3
   :preemptible_tries     3})

(def workflow-defaults
  {:unmapped_bam_suffix  ".unmapped.bam"
   :cram_ref_fasta       "gs://gcp-public-data--broad-references/hg38/v0/Homo_sapiens_assembly38.fasta"
   :cram_ref_fasta_index "gs://gcp-public-data--broad-references/hg38/v0/Homo_sapiens_assembly38.fasta.fai"
   :bait_set_name        "whole_exome_illumina_coding_v1"
   :bait_interval_list   "gs://broad-references-private/whole_exome_illumina_coding_v1/whole_exome_illumina_coding_v1.Homo_sapiens_assembly38.baits.interval_list"
   :target_interval_list "gs://broad-references-private/whole_exome_illumina_coding_v1/whole_exome_illumina_coding_v1.Homo_sapiens_assembly38.targets.interval_list"
   :references           references-defaults
   :scatter_settings     scatter-settings-defaults
   :papi_settings        papi-settings-defaults})

(defn normalize-input-items
  "The `items` of this workload are either a bucket or a list of samples.
  Normalise these `items` into a list of samples"
  [items]
  (if (string? items)
    (let [[bucket object] (gcs/parse-gs-url items)]
      (letfn [(bam-or-cram-ify [{:keys [name]}]
                (let [url (gcs/gs-url bucket name)]
                  (cond (str/ends-with? name ".bam") {:input_bam url}
                        (str/ends-with? name ".cram") {:input_cram url})))]
        (->>
          (gcs/list-objects bucket object)
          (map bam-or-cram-ify)
          (remove nil?))))
    items))

(defn make-persisted-inputs [output-url common inputs]
  (let [sample-name (fn [basename] (first (str/split basename #"\.")))
        [_ path] (gcs/parse-gs-url (some inputs [:input_bam :input_cram]))
        basename    (util/basename path)]
    (->
      (util/deep-merge common inputs)
      (util/assoc-when util/absent? :sample_name (sample-name basename))
      (util/assoc-when util/absent? :final_gvcf_base_name basename)
      (util/assoc-when util/absent? :destination_cloud_path
        (str (all/slashify output-url) (util/dirname path))))))

(defn- get-cromwell-environment [workload]
  (first
    (all/cromwell-environments
      #{:gotc-dev :gotc-prod :gotc-staging}
      (:cromwell workload))))

(defn- make-workflow-inputs [environment persisted-inputs]
  (->
    (env/stuff environment)
    (select-keys [:google_account_vault_path :vault_token_path])
    (merge (util/deep-merge workflow-defaults persisted-inputs))))

; visible for testing
(defn submit-workflows! [environment workflows]
  (throw (NoSuchMethodError. "Not Implemented")))

(defn add-workload!
  [tx {:keys [output common_inputs items] :as request}]
  (let [[uuid table] (all/add-workload-table! tx workflow-wdl request)]
    (letfn [(make-workflow-record [id items]
              (->>
                (make-persisted-inputs output common_inputs items)
                json/write-str
                (assoc {:id id} :inputs)))]
      (->>
        (normalize-input-items items)
        (map make-workflow-record (range))
        (jdbc/insert-multi! tx table))
      {:uuid uuid})))

(defn get-workload-for-uuid [tx workload-uuid]
  (when-let [workload (postgres/load-workload-for-uuid tx workload-uuid)]
    (let [environment  (get-cromwell-environment workload)
          load-inputs! #(make-workflow-inputs environment (util/parse-json %))]
      (update workload :workflows
        (fn [workflows] (mapv #(update % :inputs load-inputs!) workflows))))))

(defn start-workload!
  [tx {:keys [items workflows id] :as workload}]
  (when-not (:started workload)
    (let [environment (get-cromwell-environment workload)
          now         (OffsetDateTime/now)]
      (letfn [(update-record!
                [[id uuid status updated]]
                (let [values {:uuid uuid :status status :updated updated}]
                  (jdbc/update! tx items values ["id = ?" id])))]
        (->>
          (submit-workflows! environment workflows)
          (run! update-record!))
        (jdbc/update! tx :workload {:started now} ["id = ?" id])))))

;(def cromwell-label-map
;  "The WDL label applied to Cromwell metadata."
;  {(keyword (str wfl/the-name "-xx"))
;   (wdl/workflow-name (:top workflow-wdl))})
;
;(def cromwell-label
;  "The WDL label applied to Cromwell metadata."
;  (let [[key value] (first cromwell-label-map)]
;    (str (name key) ":" value)))
;
;(def references
;  "HG38 reference, calling interval, and contamination files."
;  (let [hg38ref "gs://gcp-public-data--broad-references/hg38/v0/"
;        contam  "Homo_sapiens_assembly38.contam"
;        calling "exome_calling_regions.v1"
;        subset  (str hg38ref contam "." calling)]
;    (merge references/hg38-exome-references
;      {:calling_interval_list   (str hg38ref calling ".interval_list")
;       :contamination_sites_bed (str subset ".bed")
;       :contamination_sites_mu  (str subset ".mu")
;       :contamination_sites_ud  (str subset ".UD")})))
;
;(def baits-and-targets
;  "Baits and targets inputs."
;  (let [private              "gs://broad-references-private/"
;        bait_set_name        "whole_exome_illumina_coding_v1"
;        interval_list        (str private "HybSelOligos/" bait_set_name "/" bait_set_name ".Homo_sapiens_assembly38")
;        target_interval_list (str interval_list ".targets.interval_list")
;        bait_interval_list   (str interval_list ".baits.interval_list")
;        fasta                "gs://gcp-public-data--broad-references/hg38/v0/Homo_sapiens_assembly38.fasta"]
;    {:bait_set_name        bait_set_name
;     :bait_interval_list   bait_interval_list
;     :target_interval_list target_interval_list
;     :cram_ref_fasta       fasta
;     :cram_ref_fasta_index (str fasta ".fai")}))
;
;(defn make-inputs
;  "Return inputs for reprocessing IN-GS into OUT-GS in ENVIRONMENT."
;  [environment out-gs in-gs]
;  (let [[input-key base _] (all/bam-or-cram? in-gs)
;        leaf   (last (str/split base #"/"))
;        [_ out-dir] (gcs/parse-gs-url (util/unsuffix base leaf))
;        inputs (-> (zipmap [:base_file_name :final_gvcf_base_name :sample_name]
;                     (repeat leaf))
;                 (assoc input-key in-gs)
;                 (assoc :destination_cloud_path (str out-gs out-dir))
;                 (assoc :references references)
;                 (merge (util/exome-inputs environment)
;                   util/gatk-docker-inputs
;                   util/gc_bias_metrics-inputs
;                   baits-and-targets))
;        {:keys [destination_cloud_path final_gvcf_base_name]} inputs
;        output (str destination_cloud_path final_gvcf_base_name ".cram")]
;    (all/throw-when-output-exists-already! output)
;    (util/prefix-keys inputs :ExternalExomeReprocessing)))
;
;(defn active-objects
;  "GCS object names of BAMs or CRAMs from IN-GS-URL now active in ENVIRONMENT."
;  [environment in-gs-url]
;  (prn (format "%s: querying Cromwell in %s" wfl/the-name environment))
;  (let [md (partial cromwell/metadata environment)]
;    (letfn [(active? [metadata]
;              (let [url (-> metadata :id md :submittedFiles :inputs
;                          (json/read-str :key-fn keyword)
;                          (some [:ExternalExomeReprocessing.input_bam
;                                 :ExternalExomeReprocessing.input_cram]))]
;                (when url
;                  (let [[bucket object] (gcs/parse-gs-url url)
;                        [_ unsuffixed _] (all/bam-or-cram? object)
;                        [in-bucket in-object] (gcs/parse-gs-url in-gs-url)]
;                    (when (and (= in-bucket bucket)
;                            (str/starts-with? object in-object))
;                      unsuffixed)))))]
;      (->> {:label  cromwell-label
;            :status ["On Hold" "Running" "Submitted"]}
;        (cromwell/query environment)
;        (keep active?)
;        set))))
;
;(defn hold-workflow
;  "Hold OBJECT from IN-BUCKET for reprocessing into OUT-GS in
;  ENVIRONMENT."
;  [environment in-bucket out-gs object]
;  (let [path  (wdl/hack-unpack-resources-hack (:top workflow-wdl))
;        in-gs (gcs/gs-url in-bucket object)]
;    (cromwell/hold-workflow
;      environment
;      (io/file (:dir path) (path ".wdl"))
;      (io/file (:dir path) (path ".zip"))
;      (make-inputs environment out-gs in-gs)
;      (util/make-options environment)
;      cromwell-label-map)
;    (prn in-gs)))
;
;(defn on-hold-some-workflows
;  "Submit 'On Hold' up to MAX workflows from IN-GS to OUT-GS
;  in ENVIRONMENT."
;  [environment max-string in-gs out-gs]
;  (let [max    (util/is-non-negative! max-string)
;        bucket (all/readable! in-gs)]
;    (letfn [(input? [{:keys [name]}]
;              (let [[_ unsuffixed suffix] (all/bam-or-cram? name)]
;                (when unsuffixed [unsuffixed suffix])))]
;      (let [done   (set/union (all/processed-crams (all/slashify out-gs))
;                     (active-objects environment in-gs))
;            suffix (->> in-gs
;                     gcs/parse-gs-url
;                     (apply gcs/list-objects)
;                     (keep input?)
;                     (into {}))
;            more   (->> suffix
;                     keys
;                     (remove done)
;                     (take max)
;                     (map (fn [base] (str base (suffix base)))))]
;        (run! (partial hold-workflow environment bucket out-gs) more)))))
;
;(defn release-some-on-hold-workflows
;  "Submit up to MAX-STRING workflows from 'On Hold' in ENVIRONMENT."
;  [environment max-string]
;  (letfn [(release! [id] (prn (cromwell/release-hold environment id)))]
;    (let [max (util/is-non-negative! max-string)]
;      (->> {:pagesize max :status "On Hold" :label cromwell-label}
;        (cromwell/query environment)
;        (map :id)
;        (take max)
;        (run! release!)))))
;

(ns wfl.service.clio
  "Manage Clio's BAM and CRAM indexes."
  (:require [clojure.data.json :as json]
            [clojure.pprint :refer [pprint]]
            [clojure.string :as str]
            [clj-http.client :as http]
            [wfl.auth :as auth]
            [wfl.util :as util]))

(defn api
  "The Clio API URL for server at `clio`."
  [clio]
  (str/join "/" [clio "api" "v1"]))

(defn post
  "Post `thing` to `clio` server with metadata `md`."
  [clio thing md]
  (-> {:url     (str/join "/" [(api clio) thing])
       :method  :post                   ; :debug true :debug-body true
       :headers (merge {"Content-Type" "application/json"}
                       (auth/get-service-account-header))
       :body    (json/write-str md :escape-slash false)}
      http/request :body
      (json/read-str :key-fn keyword)))

(def add-keys
  "The keys Clio metadata needs to add a BAM or CRAM record."
  [:location :project :data_type :sample_alias :version])

;; See ApiConstants.scala, ClioWebClientSpec.scala, and BamKey.scala and
;; CramKey.scala in Clio.
;;
;; "https://clio.gotc-dev.broadinstitute.org/api/v1/cram/metadata/GCP/G96830/WGS/NA12878/1"
;;
;; Return a Firebase Push ID string like this: "-MRu7X3zEzoGeFAVSF-J"
;;
(defn ^:private add
  "Add `thing` with metadata `md` to server at `clio`."
  [clio thing md]
  (let [need (keep (fn [k] (when-not (k md) k)) add-keys)]
    (when-not (empty need)
      (throw (ex-info "Need these metadata keys:" need)))
    (post clio
          (str/join "/" (into [thing "metadata"] ((apply juxt add-keys) md)))
          (apply dissoc md add-keys))))

(defn add-bam
  "Add a BAM entry with metadata `md` to `clio`."
  [clio md]
  (add clio "bam" md))

(defn add-cram
  "Add a CRAM entry with metadata `md` to `clio`."
  [clio md]
  (add clio "cram" md))

(defn query-bam
  "Return BAM entries with metadata `md` to `clio`."
  [clio md]
  (post clio (str/join "/" ["bam" "query"]) md))

(defn query-cram
  "Return CRAM entries with metadata `md` to `clio`."
  [clio md]
  (post clio (str/join "/" ["cram" "query"]) md))

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
  (map (comp (partial query-cram clio) sample->clio-cram)
       (util/map-tsv-file tsv)))

(defn cram->inputs
  "Translate Clio `cram` record to SG workflow inputs."
  [cram]
  (let [translation {:base_file_name :sample_alias
                     :input_cram     :cram_path}
        contam "gs://gatk-best-practices/somatic-hg38/small_exac_common_3.hg38.vcf.gz"
        fasta  "gs://gcp-public-data--broad-references/hg38/v0/Homo_sapiens_assembly38.fasta"
        dbsnp  "gs://broad-gotc-dev-storage/temp_references/gdc/dbsnp_144.hg38.vcf.gz"
        bogus  {:contamination_vcf       contam
                :contamination_vcf_index (str contam ".tbi")
                :cram_ref_fasta          fasta
                :cram_ref_fasta_index    (str fasta ".fai")
                :dbsnp_vcf               dbsnp
                :dbsnp_vcf_index         (str dbsnp ".tbi")}]
    (letfn [(translate [m [k v]] (assoc m k (v cram)))]
      {:inputs (reduce translate bogus translation)})))

(defn crams->workload
  "Return a workload request to process `crams` with SG pipeline"
  [crams]
  {:executor "https://cromwell-gotc-auth.gotc-prod.broadinstitute.org"
   :output   "gs://broad-prod-somatic-genomes-output"
   :pipeline "GDCWholeGenomeSomaticSingleSample"
   :project  "(Test) tbl/GH-1196-sg-prod-data"
   :items    (mapv cram->inputs crams)})

(comment
  (do
    (def dev  "https://clio.gotc-dev.broadinstitute.org")
    (def prod "https://clio.gotc-prod.broadinstitute.org")
    (def tsv
      "../NCI_EOMI_Ship1_WGS_SeqComplete_94samples_forGDCPipelineTesting.tsv")
    (def crams (tsv->crams prod tsv))
    (def cram (first crams)))
  (util/map-tsv-file tsv)
  (count crams)
  (query-cram dev cram)
  (query-cram prod cram)
  crams
  (crams->workload crams)
  (-> "./clio-cram-records.edn"
      clojure.java.io/file
      clojure.java.io/writer
      (->> (clojure.pprint/pprint crams)))
  cram
  )


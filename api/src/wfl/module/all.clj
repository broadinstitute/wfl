(ns wfl.module.all
  "Some utilities shared across module namespaces."
  (:require [clojure.spec.alpha         :as s]
            [clojure.string             :as str]
            [clojure.edn                :as edn]
            [wfl.jdbc                   :as jdbc]
            [wfl.service.cromwell       :as cromwell]
            [wfl.service.google.storage :as gcs]
            [wfl.service.slack          :as slack]
            [wfl.util                   :as util]
            [wfl.wfl                    :as wfl])
  (:import [java.util UUID]))

(defn throw-when-output-exists-already!
  "Throw when GS-OUTPUT-URL output exists already."
  [gs-output-url]
  (when (first (gcs/list-objects gs-output-url))
    (throw
     (IllegalArgumentException.
      (format "%s: output already exists: %s"
              wfl/the-name gs-output-url)))))

(defn bam-or-cram?
  "Nil or a vector with root of PATH and the matching suffix."
  [path]
  (or (let [bam (util/unsuffix path ".bam")]
        (when (not= bam path)
          [:input_bam bam ".bam"]))
      (let [cram (util/unsuffix path ".cram")]
        (when (not= cram path)
          [:input_cram cram ".cram"]))))

(defn readable!
  "Throw or return the bucket for GS-URL when it is readable."
  [gs-url]
  (let [[result prefix] (gcs/parse-gs-url gs-url)]
    (letfn [(ok? [bucket prefix]
              (prn (format "%s: reading: %s" wfl/the-name gs-url))
              (util/do-or-nil (gcs/list-objects bucket prefix)))]
      (when-not (ok? result prefix)
        (throw (IllegalArgumentException.
                (format "%s must be readable" gs-url)))))
    result))

(defn processed-crams
  "Root object names of CRAMs in OUT-GS."
  [out-gs]
  (let [[bucket prefix] (gcs/parse-gs-url out-gs)]
    (letfn [(cram? [{:keys [name]}]
              (when (str/ends-with? name ".cram")
                (-> name
                    (util/unsuffix ".cram")
                    (subs (count prefix)))))]
      (->> (gcs/list-objects bucket prefix)
           (keep cram?)
           set))))

(defn count-files
  "Count the BAM or CRAM files in IN-GS, and the BAM or CRAM files
  in OUT-GS."
  [in-gs out-gs]
  (letfn [(crams [gs-url]
            (prn (format "%s: reading: %s" wfl/the-name gs-url))
            [gs-url (->> (gcs/list-objects gs-url)
                         (keep (comp bam-or-cram? :name))
                         count)])]
    (let [gs-urls (into (array-map) (map crams [in-gs out-gs]))
          remaining (apply - (map gs-urls [in-gs out-gs]))]
      (into gs-urls [[:remaining remaining]]))))

(defn add-workload-table!
  "Return ID and TABLE for _WORKFLOW-WDL in BODY under transaction TX."
  [tx {:keys [release path] :as _workflow-wdl} body]
  (let [{:keys [creator executor input output pipeline project watchers]} body
        {:keys [commit version]} (wfl/get-the-version)
        [{:keys [id]}]
        (jdbc/insert! tx :workload {:commit   commit
                                    :creator  creator
                                    :executor executor
                                    :input    input
                                    :output   output
                                    :project  project
                                    :release  release
                                    :uuid     (UUID/randomUUID)
                                    :version  version
                                    :watchers (mapv prn-str watchers)
                                    :wdl      path})
        table (format "%s_%09d" pipeline id)
        work (format "CREATE TABLE %s OF %s (PRIMARY KEY (id))"
                     table pipeline)]
    (jdbc/update! tx :workload {:items table} ["id = ?" id])
    (jdbc/execute! tx ["UPDATE workload SET pipeline = ?::pipeline WHERE id = ?" pipeline id])
    (jdbc/db-do-commands tx [work])
    [id table]))

(defn has?
  "Return a function that takes a map and returns the result of applying the
   `predicate?` to the value at the `key` in `map`, if one exists."
  [key predicate?]
  (fn [map]
    (when-let [value (get map key)]
      (predicate? value))))

;; shared specs
(s/def ::base_file_name string?)
(s/def ::commit (s/and string? (comp (partial == 40) count)))
(s/def ::contamination_vcf string?)
(s/def ::contamination_vcf_index string?)
(s/def ::cram_ref_fasta string?)
(s/def ::cram_ref_fasta_index string?)
(s/def ::timestamp (s/or :instant inst? :datetime util/datetime-string?))
(s/def ::created ::timestamp)
(s/def ::cromwell string?)
(s/def ::dataset string?)
(s/def ::dbsnp_vcf string?)
(s/def ::dbsnp_vcf_index string?)
(s/def ::environment string?)
(s/def ::finished ::timestamp)
(s/def ::input string?)
(s/def ::input_bam #(str/ends-with? % ".bam"))
(s/def ::input_cram #(str/ends-with? % ".cram"))
(s/def ::output string?)
(s/def ::pipeline string?)
(s/def ::project string?)
(s/def ::release string?)
(s/def ::status cromwell/status?)
(s/def ::started ::timestamp)
(s/def ::stopped ::timestamp)
(s/def ::table string?)
(s/def ::updated ::timestamp)
(s/def ::uuid util/uuid-string?)
(s/def ::uuid-kv (s/keys :req-un [::uuid]))
(s/def ::version string?)
(s/def ::wdl string?)
(s/def ::options map?)
(s/def ::common map?)

(s/def ::entityType string?)
(s/def ::fromSource string?)
(s/def ::labels (s/* util/label?))
(s/def ::name string?)
(s/def ::methodConfiguration (s/and string? util/terra-namespaced-name?))
(s/def ::methodConfigurationVersion integer?)
(s/def ::email util/email-address?)
(s/def ::legacyWatcher ::email)
(s/def ::taggedWatcher (s/or :email slack/email-watcher?
                             :slack slack/slack-channel-watcher?))
(s/def ::watcher
  (s/or :legacy_watcher  ::legacyWatcher
        :tagged_watcher  ::taggedWatcher))
(s/def ::watchers (s/* ::watcher))
(s/def ::workspace (s/and string? util/terra-namespaced-name?))

(defn parse-watcher
  "Parse watchers based on specs."
  [watcher]
  (if (s/valid? ::legacyWatcher watcher)
    watcher
    (edn/read-string watcher)))

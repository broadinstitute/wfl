(ns wfl.module.all
  "Some utilities shared across module namespaces."
  (:require [clojure.spec.alpha   :as s]
            [clojure.string       :as str]
            [wfl.jdbc             :as jdbc]
            [wfl.service.cromwell :as cromwell]
            [wfl.service.slack    :as slack]
            [wfl.util             :as util]
            [wfl.wfl              :as wfl])
  (:import [java.util UUID]))

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
                                    :watchers (pr-str watchers)
                                    :wdl      path})
        table (format "%s_%09d" pipeline id)
        work (format "CREATE TABLE %s OF %s (PRIMARY KEY (id))"
                     table pipeline)]
    (jdbc/update! tx :workload {:items table} ["id = ?" id])
    (jdbc/execute! tx ["UPDATE workload SET pipeline = ?::pipeline WHERE id = ?" pipeline id])
    (jdbc/db-do-commands tx [work])
    [id table]))

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
(s/def ::labels (s/* util/label?))
(s/def ::name string?)
(s/def ::watcher
  (s/or :email slack/email-watcher?
        :slack slack/slack-channel-watcher?))
(s/def ::watchers (s/* ::watcher))
(s/def ::workspace (s/and string? util/terra-namespaced-name?))

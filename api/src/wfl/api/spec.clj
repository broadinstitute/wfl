(ns wfl.api.spec
  "Define specs used in routes"
  (:require [clojure.spec.alpha   :as s]
            [clojure.string       :as str]
            [wfl.service.cromwell :as cromwell]
            [wfl.util             :as util])
  (:import [java.time OffsetDateTime]
           [java.util UUID]
           [javax.mail.internet InternetAddress]))

(defn uuid-string? [s] (uuid? (util/do-or-nil (UUID/fromString s))))
(defn datetime-string? [s] (util/do-or-nil (OffsetDateTime/parse s)))

(defn email-address?
  "True if `s` is an email address."
  [s]
  (util/do-or-nil (or (.validate (InternetAddress. s)) true)))

;; shared
(s/def ::base_file_name string?)
(s/def ::commit (s/and string? (comp (partial == 40) count)))
(s/def ::contamination_vcf string?)
(s/def ::contamination_vcf_index string?)
(s/def ::cram_ref_fasta string?)
(s/def ::cram_ref_fasta_index string?)
(s/def ::timestamp (s/or :instant inst? :datetime datetime-string?))
(s/def ::created ::timestamp)
(s/def ::creator email-address?)
(s/def ::cromwell string?)
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
(s/def ::status (set (conj cromwell/statuses "skipped")))
(s/def ::started ::timestamp)
(s/def ::stopped ::timestamp)
(s/def ::updated ::timestamp)
(s/def ::uuid uuid-string?)
(s/def ::uuid-kv (s/keys :req-un [::uuid]))
(s/def ::version string?)
(s/def ::wdl string?)
(s/def ::options map?)
(s/def ::common map?)
(s/def ::workload-query (s/and (s/keys :opt-un [::uuid ::project])
                               #(not (and (:uuid %) (:project %)))))

;; compound
(s/def ::items (s/* ::workload-inputs))
(s/def ::workload-inputs (s/keys :req-un [::inputs]
                                 :opt-un [::options]))
(s/def ::inputs (s/or :aou      ::aou-workflow-inputs
                      :arrays   ::arrays-workflow-inputs
                      :copyfile ::copyfile-workflow-inputs
                      :wgs      ::wgs-workflow-inputs
                      :xx       ::xx-workflow-inputs
                      :sg       ::sg-workflow-inputs))

(s/def ::workflows (s/* ::workflow))
(s/def ::workflow  (s/keys :req-un [::inputs]
                           :opt-un [::status ::updated ::uuid ::options]))

;; aou
(s/def ::analysis_version_number integer?)
(s/def ::chip_well_barcode string?)
(s/def ::append-to-aou-request (s/keys :req-un [::notifications ::uuid]))
(s/def ::append-to-aou-response (s/* ::aou-workflow-inputs))
(s/def ::aou-workflow-inputs (s/keys :req-un [::analysis_version_number
                                              ::chip_well_barcode]))

(s/def ::notifications (s/* ::aou-sample))
(s/def ::aou-sample (s/keys :req-un [::analysis_version_number
                                     ::chip_well_barcode]))

;; arrays
(s/def ::entity-name string?)
(s/def ::entity-type string?)
(s/def ::arrays-workflow-inputs (s/keys :req-un [::entity-name
                                                 ::entity-type]))

;; copyfile
(s/def ::copyfile-workflow-inputs (s/keys :req-un [::dst ::src]))
(s/def ::dst string?)
(s/def ::src string?)

;; wgs (External Whole Genome Reprocessing)
(s/def ::wgs-workflow-inputs (s/keys :req-un [(or ::input_bam ::input_cram)]))

;; xx (External Exome Reprocessing)
(s/def ::xx-workflow-inputs (s/keys :req-un [(or ::input_bam ::input_cram)]))

;; sg (GDC Whole Genome Somatic single Sample)
(s/def ::sg-workflow-inputs (s/keys :req-un [::base_file_name
                                             ::contamination_vcf
                                             ::contamination_vcf_index
                                             ::cram_ref_fasta
                                             ::cram_ref_fasta_index
                                             ::dbsnp_vcf
                                             ::dbsnp_vcf_index
                                             ::input_cram]))

(s/def ::column string?)
(s/def ::dataset string?)
(s/def ::entityType string?)
(s/def ::identifier string?)
(s/def ::fromOutputs map?)
(s/def ::fromSource string?)
(s/def ::labels (s/* util/label?))
(s/def ::name string?)
(s/def ::methodConfiguration (s/and string? util/terra-namespaced-name?))
(s/def ::methodConfigurationVersion integer?)
(s/def ::table string?)
(s/def ::snapshotReaders (s/* email-address?))
(s/def ::watchers (s/* email-address?))
(s/def ::workspace (s/and string? util/terra-namespaced-name?))
(s/def ::snapshots (s/* ::uuid))

(s/def :batch/executor string?)
(s/def :covid/executor (s/keys :req-un [::name
                                        ::fromSource
                                        ::methodConfiguration
                                        ::methodConfigurationVersion
                                        ::workspace]))

(s/def ::sink (s/keys :req-un [::name
                               ::entityType
                               ::fromOutputs
                               ::identifier
                               ::workspace]))

(s/def ::tdr-source
  (s/keys :req-un [::name
                   ::column
                   ::dataset
                   ::table
                   ::snapshotReaders]
          :opt-un [::snapshots]))

(s/def ::snapshot-list-source
  (s/keys :req-un [::name ::snapshots]))

(s/def ::source (s/or :dataset   ::tdr-source
                      :snapshots ::snapshot-list-source))

;; This is the wrong thing to do. See [1] for more information.
;; As a consequence, I've included the keys for a covid pipeline as optional
;; inputs for batch workloads so that these keys are not removed during
;; coercion.
;; [1]: https://github.com/metosin/reitit/issues/494
(s/def ::batch-workload-request
  (s/keys :opt-un [::common
                   ::input
                   ::items
                   ::labels
                   ::output
                   ::sink
                   ::source
                   ::watchers]
          :req-un [(or ::cromwell :batch/executor)
                   ::pipeline
                   ::project]))

(s/def ::batch-workload-response (s/keys :opt-un [::finished
                                                  ::input
                                                  ::started
                                                  ::stopped
                                                  ::wdl
                                                  ::workflows]
                                         :req-un [::commit
                                                  ::created
                                                  ::creator
                                                  :batch/executor
                                                  ::output
                                                  ::pipeline
                                                  ::project
                                                  ::release
                                                  ::uuid
                                                  ::version]))

(s/def ::covid-workload-request (s/keys :req-un [:covid/executor
                                                 ::project
                                                 ::sink
                                                 ::source]
                                        :opt-un [::labels
                                                 ::watchers]))

(s/def ::covid-workload-response (s/keys :req-un [::created
                                                  ::creator
                                                  :covid/executor
                                                  ::labels
                                                  ::sink
                                                  ::source
                                                  ::uuid
                                                  ::version
                                                  ::watchers]
                                         :opt-un [::finished
                                                  ::started
                                                  ::stopped
                                                  ::updated]))

(s/def ::workload-request (s/or :batch ::batch-workload-request
                                :covid ::covid-workload-request))

(s/def ::workload-response (s/or :batch ::batch-workload-response
                                 :covid ::covid-workload-response))

(s/def ::workload-responses (s/* ::workload-response))

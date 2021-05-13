(ns wfl.api.spec
  "Define specs used in routes"
  (:require [wfl.service.cromwell :as cromwell]
            [clojure.spec.alpha :as s]
            [wfl.util :as util]
            [clojure.string :as str])
  (:import [java.util UUID]))

(defn uuid-string? [s] (uuid? (util/do-or-nil (UUID/fromString s))))

;; shared
(s/def ::base_file_name string?)
(s/def ::common map?)
(s/def ::commit (s/and string? (comp (partial == 40) count)))
(s/def ::contamination_vcf string?)
(s/def ::contamination_vcf_index string?)
(s/def ::cram_ref_fasta string?)
(s/def ::cram_ref_fasta_index string?)
(s/def ::created inst?)
(s/def ::creator string?)
(s/def ::cromwell string?)
(s/def ::dataset string?)
(s/def ::dbsnp_vcf string?)
(s/def ::dbsnp_vcf_index string?)
(s/def ::entity string?)
(s/def ::environment string?)
(s/def ::finished inst?)
(s/def ::from_outputs map?)
(s/def ::from_source string?)
(s/def ::input string?)
(s/def ::input_bam #(str/ends-with? % ".bam"))
(s/def ::input_cram #(str/ends-with? % ".cram"))
(s/def ::labels (s/coll-of string?))
(s/def ::name string?)
(s/def ::method_configuration string?)
(s/def ::options map?)
(s/def ::output string?)
(s/def ::pipeline string?)
(s/def ::project string?)
(s/def ::release string?)
(s/def ::snapshot string?)
(s/def ::started inst?)
(s/def ::status (set (conj cromwell/statuses "skipped")))
(s/def ::stopped inst?)
(s/def ::dataset_table string?)
(s/def ::updated inst?)
(s/def ::uuid (s/and string? uuid-string?))
(s/def ::version string?)
(s/def ::watchers (s/coll-of string?))
(s/def ::wdl string?)
(s/def ::workspace string?)

(s/def ::executor (s/or :legacy_executor string?
                        :executor (s/keys :req-un [::name
                                                   ::workspace
                                                   ::method_configuration
                                                   ::version
                                                   ::entity
                                                   ::from_source])))

(s/def ::sink (s/keys :req-un [::name
                               ::workspace
                               ::entity
                               ::from_outputs]))

(s/def ::source (s/keys :req-un [::name
                                 ::dataset
                                 ::dataset_table
                                 ::snapshot]))

(s/def ::uuid-kv (s/keys :req-un [::uuid]))

(s/def ::workload-query (s/and (s/keys :opt-un [::uuid ::project])
                               #(not (and (:uuid %) (:project %)))))

(s/def ::workload-request (s/or :legacy-workload-request (s/keys :opt-un [::common
                                                                          ::input
                                                                          ::items]
                                                                 :req-un [(or ::executor ::cromwell)
                                                                          ::output
                                                                          ::pipeline
                                                                          ::project])
                                :covid-workload-request (s/keys :opt-un [::labels
                                                                         ::watchers]
                                                                :req-un [::sink
                                                                         ::executor
                                                                         ::source])))
(s/def ::workload-response (s/keys :opt-un [::finished
                                            ::input
                                            ::started
                                            ::stopped
                                            ::wdl
                                            ::workflows]
                                   :req-un [::commit
                                            ::created
                                            ::creator
                                            ::executor
                                            ::output
                                            ::pipeline
                                            ::project
                                            ::release
                                            ::uuid
                                            ::version]))
(s/def ::workload-responses (s/* ::workload-response))


;; compound
(s/def ::items (s/* ::workload-inputs))
(s/def ::inputs (s/or :aou      ::aou-workflow-inputs
                      :arrays   ::arrays-workflow-inputs
                      :copyfile ::copyfile-workflow-inputs
                      :wgs      ::wgs-workflow-inputs
                      :xx       ::xx-workflow-inputs
                      :sg       ::sg-workflow-inputs))
(s/def ::workflow
  (s/keys :req-un [::inputs]
          :opt-un [::status ::updated ::uuid ::options]))
(s/def ::workflows (s/* ::workflow))
(s/def ::workload-inputs (s/keys :req-un [::inputs]
                                 :opt-un [::options]))

;; aou
(s/def ::analysis_version_number integer?)
(s/def ::aou-workflow-inputs (s/keys :req-un [::analysis_version_number
                                              ::chip_well_barcode]))
(s/def ::aou-sample (s/keys :req-un [::analysis_version_number
                                     ::chip_well_barcode]))
(s/def ::append-to-aou-request (s/keys :req-un [::notifications ::uuid]))
(s/def ::append-to-aou-response (s/* ::aou-workflow-inputs))
(s/def ::chip_well_barcode string?)
(s/def ::notifications (s/* ::aou-sample))

;; arrays
(s/def ::entity-name string?)
(s/def ::entity-type string?)
(s/def ::arrays-workflow-inputs (s/keys :req-un [::entity-name
                                                 ::entity-type]))

;; copyfile
(s/def ::dst string?)
(s/def ::src string?)
(s/def ::copyfile-workflow-inputs (s/keys :req-un [::dst ::src]))

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

;; /api/v1/workflows
(s/def ::start string?)
(s/def ::end string?)
(s/def ::workflow-request (s/keys :req-un [::end
                                           ::environment
                                           ::start]))

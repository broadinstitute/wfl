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
(s/def ::commit (s/and string? (comp (partial == 40) count)))
(s/def ::contamination_vcf string?)
(s/def ::contamination_vcf_index string?)
(s/def ::cram_ref_fasta string?)
(s/def ::cram_ref_fasta_index string?)
(s/def ::created inst?)
(s/def ::creator string?)
(s/def ::cromwell string?)
(s/def ::dbsnp_vcf string?)
(s/def ::dbsnp_vcf_index string?)
(s/def ::environment string?)
(s/def ::finished inst?)
(s/def ::input string?)
(s/def ::input_bam #(str/ends-with? % ".bam"))
(s/def ::input_cram #(str/ends-with? % ".cram"))
(s/def ::output string?)
(s/def ::pipeline string?)
(s/def ::project string?)
(s/def ::release string?)
(s/def ::status (set (conj cromwell/statuses "skipped")))
(s/def ::started inst?)
(s/def ::stopped inst?)
(s/def ::updated inst?)
(s/def ::uuid (s/and string? uuid-string?))
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
(s/def ::workflow
  (s/keys :req-un [::inputs]
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

;; /api/v1/workflows
(s/def ::start string?)
(s/def ::end string?)
(s/def ::workflow-request (s/keys :req-un [::end
                                           ::environment
                                           ::start]))


(s/def ::batch-executor string?)
(s/def ::covid-executor (s/keys :req-un [::fromSource
                                         ::methodConfiguration
                                         ::methodConfigurationVersion
                                         ::name
                                         ::workspace]))

(s/def ::executor (s/or :batch ::batch-executor
                        :covid ::covid-executor))

(s/def ::column string?)
(s/def ::dataset string?)
(s/def ::entity string?)
(s/def ::fromOutputs map?)
(s/def ::fromSource string?)
(s/def ::labels (s/* string?))
(s/def ::name string?)
(s/def ::methodConfiguration string?)
(s/def ::methodConfigurationVersion integer?)
(s/def ::table string?)
(s/def ::watchers (s/* string?))
(s/def ::workspace (s/and string? util/namespaced-workspace-name?))

(s/def ::sink (s/keys :req-un [::entity
                               ::fromOutputs
                               ::workspace]))

(s/def ::source (s/keys :req-un [::column
                                 ::dataset
                                 ::table]))

(s/def ::batch-workload-request (s/keys :opt-un [::common
                                                 ::input
                                                 ::items
                                                 ::output]
                                        :req-un [(or ::cromwell ::executor)
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
                                                  ::executor
                                                  ::output
                                                  ::pipeline
                                                  ::project
                                                  ::release
                                                  ::uuid
                                                  ::version]))

(s/def ::covid-workload-request (s/keys :req-un [::executor
                                                 ::pipeline
                                                 ::sink
                                                 ::source]
                                        :opt-un [::labels
                                                 ::project
                                                 ::watchers]))

(s/def ::covid-workload-response (s/keys :opt-un [::finished
                                                  ::release
                                                  ::started
                                                  ::stopped]))

(s/def ::workload-request (s/or :batch ::batch-workload-request
                                :covid ::covid-workload-request))

(s/def ::workload-response (s/or :batch ::batch-workload-response
                                 :covid ::covid-workload-response))

(s/def ::workload-responses (s/* ::workload-response))

(s/def ::whatever (constantly true))

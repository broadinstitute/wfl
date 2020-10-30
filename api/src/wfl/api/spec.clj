(ns wfl.api.spec
  "Define specs used in routes"
  (:require [wfl.service.cromwell :as cromwell]
            [clojure.spec.alpha :as s]
            [wfl.util :as util])
  (:import [java.util UUID]))

(defn uuid-string? [s] (uuid? (util/do-or-nil (UUID/fromString s))))

;; shared
(s/def ::commit (s/and string? (comp (partial == 40) count)))
(s/def ::created inst?)
(s/def ::creator string?)
(s/def ::cromwell string?)
(s/def ::environment string?)
(s/def ::finished inst?)
(s/def ::input string?)
(s/def ::input_bam string?)
(s/def ::input_cram string?)
(s/def ::output string?)
(s/def ::common_inputs map?)
(s/def ::pipeline string?)
(s/def ::project string?)
(s/def ::release string?)
(s/def ::status (set (conj cromwell/statuses "skipped")))
(s/def ::started inst?)
(s/def ::updated inst?)
(s/def ::uuid (s/and string? uuid-string?))
(s/def ::uuid-kv (s/keys :req-un [::uuid]))
(s/def ::uuid-query (s/keys :opt-un [::uuid]))
(s/def ::version string?)
(s/def ::wdl string?)
(s/def ::workflow_options map?)
(s/def ::workload-request (s/keys :opt-un [::input
                                           ::items
                                           ::common_inputs
                                           ::workflow_options]
                                  :req-un [::cromwell
                                           ::output
                                           ::pipeline
                                           ::project]))
(s/def ::workload-response (s/keys :opt-un [::finished
                                            ::input
                                            ::started
                                            ::wdl
                                            ::workflows
                                            ::workflow_options]
                                   :req-un [::commit
                                            ::created
                                            ::creator
                                            ::cromwell
                                            ::output
                                            ::pipeline
                                            ::project
                                            ::release
                                            ::uuid
                                            ::version]))
(s/def ::workload-responses (s/* ::workload-response))

;; compound
(s/def ::items (s/* ::workload-inputs))
(s/def ::workload-inputs (s/keys :req-un [::inputs]
                                 :opt-un [::workflow_options]))
(s/def ::inputs (s/or :aou      ::aou-workflow-inputs
                      :copyfile ::copyfile-workflow-inputs
                      :wgs      ::wgs-workflow-inputs
                      :xx       ::xx-workflow-inputs))

(s/def ::workflows (s/* ::workflow))
(s/def ::workflow
  (s/keys :req-un [::inputs]
          :opt-un [::status ::updated ::uuid ::workflow_options]))

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

;; copyfile
(s/def ::copyfile-workflow-inputs (s/keys :req-un [::dst ::src]))
(s/def ::dst string?)
(s/def ::src string?)

;; wgs
(s/def ::base_file_name string?)
(s/def ::final_gvcf_base_name string?)
(s/def ::reference_fasta_prefix string?)
(s/def ::sample_name string?)
(s/def ::unmapped_bam_suffix string?)
(s/def ::wgs-workflow-inputs (s/keys :opt-un [::base_file_name
                                              ::final_gvcf_base_name
                                              ::reference_fasta_prefix
                                              ::sample_name
                                              ::unmapped_bam_suffix]
                                     :req-un [::input_cram]))

;; xx (External Exome Reprocessing)
(s/def ::xx-workflow-inputs (s/keys :req-un [(or ::input_bam ::input_cram)]))

;; /api/v1/workflows
(s/def ::start string?)
(s/def ::end string?)
(s/def ::workflow-request (s/keys :req-un [::end
                                           ::environment
                                           ::start]))

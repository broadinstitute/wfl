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
(s/def ::output string?)
(s/def ::common_inputs map?)
(s/def ::pipeline string?)
(s/def ::project string?)
(s/def ::release string?)
(s/def ::status (conj (set cromwell/statuses) "skipped"))
(s/def ::started inst?)
(s/def ::updated inst?)
(s/def ::uuid (s/and string? uuid-string?))
(s/def ::uuids (s/* ::uuid))
(s/def ::uuid-kv (s/keys :req-un [::uuid]))
(s/def ::uuid-kvs (s/* ::uuid-kv))
(s/def ::uuid-query (s/keys :opt-un [::uuid]))
(s/def ::version string?)
(s/def ::wdl string?)
(s/def ::workload-request (s/keys :opt-un [::input
                                           ::common_inputs]
                                  :req-un [::cromwell
                                           ::items
                                           ::output
                                           ::pipeline
                                           ::project]))
(s/def ::workload-response (s/keys :opt-un [::finished
                                            ::input
                                            ::started
                                            ::wdl
                                            ::workflows]
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
(s/def ::items (s/or :filesystem ::filesystem-inputs
                     :indivudual (s/+ ::individual-inputs)))
(s/def ::filesystem-inputs (s/or :xx ::bucket))
(s/def ::individual-inputs (s/keys :opt-un [::inputs]))

(s/def ::workflows (s/* (s/keys :opt-un [::inputs
                                         ::updated
                                         ::uuid
                                         ::status])))

(s/def ::inputs (s/or :aou ::aou-workflow-inputs
                      :wgs ::wgs-workflow-inputs
                      :xx  ::xx-workflow-inputs))

;; aou
(s/def ::analysis_version_number integer?)
(s/def ::append-to-aou-request (s/keys :req-un [::cromwell
                                                ::environment
                                                ::notifications
                                                ::uuid]))
(s/def ::append-to-aou-response (s/*
                                  (s/or :none empty?
                                        :more (s/keys :req-un [::uuid
                                                               ::analysis_version_number
                                                               ::chip_well_barcode]))))
(s/def ::chip_well_barcode string?)
(s/def ::aou-workflow-inputs (constantly true))     ; stub
(s/def ::notifications (s/* map?))

;; wgs
(s/def ::base_file_name string?)
(s/def ::final_gvcf_base_name string?)
(s/def ::input_cram string?)
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
(s/def ::url string?) ; ideally test that it's a valid url too
(s/def ::bucket ::url)
(s/def ::samples (s/+ ::url))
(s/def ::input_bam string?)
(s/def ::xx-workflow-inputs map?)

;; /api/v1/workflows
(s/def ::start string?)
(s/def ::end string?)
(s/def ::workflow-request (s/keys :req-un [::end
                                           ::environment
                                           ::start]))

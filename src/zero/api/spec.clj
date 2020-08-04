(ns zero.api.spec
  "Define specs used in routes"
  (:require [zero.service.cromwell :as cromwell]
            [clojure.spec.alpha :as s]
            [zero.util :as util])
  (:import [java.util UUID]))

(defn uuid-string? [s] (uuid? (util/do-or-nil (UUID/fromString s))))

;; shared
(s/def ::commit (s/and string? (comp (partial == 40) count)))
(s/def ::created inst?)
(s/def ::creator string?)
(s/def ::cromwell string?)
(s/def ::environment string?)
(s/def ::finished inst?)
(s/def ::id pos-int?)
(s/def ::input string?)
(s/def ::output string?)
(s/def ::pipeline string?)
(s/def ::project string?)
(s/def ::release string?)
(s/def ::status (set cromwell/statuses))
(s/def ::started inst?)
(s/def ::updated inst?)
(s/def ::uuid (s/and string? uuid-string?))
(s/def ::uuids (s/* ::uuid))
(s/def ::uuid-kv (s/keys :req-un [::uuid]))
(s/def ::uuid-kvs (s/* ::uuid-kv))
(s/def ::uuid-query (s/or :none empty?
                          :one (s/keys :req-un [::uuid])))
(s/def ::version string?)
(s/def ::wdl string?)
(s/def ::workload-request (s/keys :req-un [::creator
                                           ::cromwell
                                           ::input
                                           ::items
                                           ::output
                                           ::pipeline
                                           ::project]))
(s/def ::workload-response (s/keys :opt-un [::finished
                                            ::pipeline
                                            ::started
                                            ::wdl
                                            ::workflows]
                                   :req-un [::commit
                                            ::created
                                            ::creator
                                            ::cromwell
                                            ::id
                                            ::input
                                            ::output
                                            ::project
                                            ::release
                                            ::uuid
                                            ::version]))
(s/def ::workload-responses (s/* ::workload-response))

;; compound
(s/def ::items (s/or :aou (s/+ ::items-aou)
                     :wgs (s/+ ::items-wgs)))
(s/def ::workflows (s/or :aou (s/* ::workflow-aou)
                         :wgs (s/+ ::workflow-wgs)))

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
(s/def ::items-aou (constantly true))     ; stub
(s/def ::notifications (s/* map?))
(s/def ::workflow-aou map?)               ; stub

;; wgs
(s/def ::base_file_name string?)
(s/def ::final_gvcf_base_name string?)
(s/def ::input_cram string?)
(s/def ::items-wgs (s/keys :opt-un [::base_file_name
                                    ::final_gvcf_base_name
                                    ::unmapped_bam_suffix]
                           :req-un [::input_cram
                                    ::sample_name]))
(s/def ::sample_name string?)
(s/def ::unmapped_bam_suffix string?)
(s/def ::workflow-wgs (s/keys :opt-un [::base_file_name
                                       ::final_gvcf_base_name
                                       ::status
                                       ::unmapped_bam_suffix
                                       ::updated
                                       ::uuid]
                              :req-un [::id
                                       ::input_cram
                                       ::sample_name]))

;; /api/v1/workflows
(s/def ::start string?)
(s/def ::end string?)
(s/def ::workflow-request (s/keys :req-un [::end
                                           ::environment
                                           ::start]))

(ns wfl.api.spec
  "Define specs used in routes"
  (:require [clojure.spec.alpha   :as s]
            [clojure.string       :as str]
            [wfl.util             :as util]
            [wfl.module.covid     :as covid]
            [wfl.module.all       :as all]))

;; shared
(s/def ::workload-query (s/and (s/keys :opt-un [::all/uuid ::all/project])
                               #(not (and (:uuid %) (:project %)))))

(s/def :version/built     util/datetime-string?)
(s/def :version/commit    (s/and string? (comp not str/blank?)))
(s/def :version/committed util/datetime-string?)
(s/def :version/user      (s/and string? (comp not str/blank?)))

(s/def ::version-response (s/keys :req-un [:version/built
                                           :version/commit
                                           :version/committed
                                           :version/user
                                           ::version]))

;; compound
(s/def ::items (s/* ::workload-inputs))
(s/def ::workload-inputs (s/keys :req-un [::inputs]
                                 :opt-un [::all/options]))
(s/def ::inputs (s/or :aou      ::aou-workflow-inputs
                      :copyfile ::copyfile-workflow-inputs
                      :wgs      ::wgs-workflow-inputs
                      :xx       ::xx-workflow-inputs
                      :sg       ::sg-workflow-inputs
                      :other    map?))

(s/def ::workflow  (s/keys :req-un [::inputs]
                           :opt-un [::all/status ::all/updated ::all/uuid ::all/options]))
(s/def ::workflows (s/* ::workflow))

;; aou
(s/def ::analysis_version_number integer?)
(s/def ::chip_well_barcode string?)
(s/def ::append-to-aou-request (s/keys :req-un [::notifications ::all/uuid]))
(s/def ::append-to-aou-response (s/* ::aou-workflow-inputs))
(s/def ::aou-workflow-inputs (s/keys :req-un [::analysis_version_number
                                              ::chip_well_barcode]))

(s/def ::notifications (s/* ::aou-sample))
(s/def ::aou-sample (s/keys :req-un [::analysis_version_number
                                     ::chip_well_barcode]))

(s/def ::entity-name string?)
(s/def ::entity-type string?)

;; copyfile
(s/def ::copyfile-workflow-inputs (s/keys :req-un [::dst ::src]))
(s/def ::dst string?)
(s/def ::src string?)

;; wgs (External Whole Genome Reprocessing)
(s/def ::wgs-workflow-inputs (s/keys :req-un [(or ::all/input_bam ::all/input_cram)]))

;; xx (External Exome Reprocessing)
(s/def ::xx-workflow-inputs (s/keys :req-un [(or ::all/input_bam ::all/input_cram)]))

;; sg (GDC Whole Genome Somatic single Sample)
(s/def ::sg-workflow-inputs (s/keys :req-un [::all/base_file_name
                                             ::all/contamination_vcf
                                             ::all/contamination_vcf_index
                                             ::all/cram_ref_fasta
                                             ::all/cram_ref_fasta_index
                                             ::all/dbsnp_vcf
                                             ::all/dbsnp_vcf_index
                                             ::all/input_cram]))

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
(s/def ::snapshotReaders (s/* util/email-address?))
(s/def ::watchers (s/* util/email-address?))
(s/def ::workspace (s/and string? util/terra-namespaced-name?))
(s/def ::snapshots (s/* ::all/uuid))

(s/def :batch/executor string?)

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
  (s/keys :opt-un [::all/common
                   ::all/input
                   ::all/items
                   ::labels
                   ::all/output
                   ::sink
                   ::source
                   ::watchers]
          :req-un [(or ::all/cromwell :batch/executor)
                   ::all/pipeline
                   ::all/project]))

(s/def ::batch-workload-response (s/keys :opt-un [::all/finished
                                                  ::all/input
                                                  ::all/started
                                                  ::all/stopped
                                                  ::all/wdl]
                                         :req-un [::all/commit
                                                  ::all/created
                                                  :batch/creator
                                                  :batch/executor
                                                  ::all/output
                                                  ::all/pipeline
                                                  ::all/project
                                                  ::all/release
                                                  ::all/uuid
                                                  ::version]))

(s/def ::workload-request (s/or :batch ::batch-workload-request
                                :covid ::covid/workload-request))

(s/def ::workload-response (s/or :batch ::batch-workload-response
                                 :covid ::covid/workload-response))

(s/def ::workload-responses (s/* ::workload-response))

(ns wfl.system.automation-test
  (:require [clojure.data.json :as json]
            [clojure.test :refer [deftest is]]
            [wfl.environment :as env]
            [wfl.service.datarepo :as datarepo]
            [wfl.service.google.storage :as storage]
            [wfl.tools.datasets :as datasets]
            [wfl.tools.fixtures :as fixtures]
            [wfl.tools.workflows :as workflows])
  (:import (java.util UUID)))

(defn ^:private replace-urls-with-file-ids
  [file->fileid type value]
  (-> (fn [type value]
        (case type
          ("Boolean" "Float" "Int" "Number" "String") value
          "File"                                      (file->fileid value)
          (throw (ex-info "Unknown type" {:type type :value value}))))
      (workflows/traverse type value)))

(deftest test-automate-sarscov2-illumina-full
  (let [tdr-profile (env/getenv "WFL_TDR_DEFAULT_PROFILE")]
    (fixtures/with-fixtures
      [(fixtures/with-temporary-cloud-storage-folder
         "broad-gotc-dev-wfl-ptc-test-inputs")
       (fixtures/with-temporary-dataset
         (datasets/unique-dataset-request
          tdr-profile
          "sarscov2-illumina-full-inputs.json"))
       (fixtures/with-temporary-dataset
         (datasets/unique-dataset-request
          tdr-profile
          "sarscov2-illumina-full-outputs.json"))]
      (fn [[temp source sink]]
        ;; TODO: create + start the workload
        ;; upload a sample
        (let [inputs        (workflows/read-resource "sarscov2_illumina_full/inputs")
              inputs-type   (-> "sarscov2_illumina_full/description"
                                workflows/read-resource
                                :inputs
                                workflows/make-object-type)
              table-name    "sarscov2_illumina_full_inputs"
              unique-prefix (UUID/randomUUID)
              table-url     (str temp "inputs.json")

              ;; Proposed nomenclature:
              ;; An `inputmap` tells workflow-launcher how to map names into
              ;; pipeline inputs.
              ;; An `outputmap` tells workflow-launcher how to names into
              ;; dataset table columns.
              ;;
              ;; I think a user would specify something like this in the initial
              ;; workload request.
              outputmap     {:flowcell_tgz         :flowcell_tgz
                             :reference_fasta      :reference_fasta
                             :amplicon_bed_prefix  :amplicon_bed_prefix
                             :biosample_attributes :biosample_attributes
                             :instrument_model     :instrument_model
                             :min_genome_bases     :min_genome_bases
                             :max_vadr_alerts      :max_vadr_alerts
                             :sra_title            :sra_title
                             :workspace_name       "$SARSCoV2-Illumina-Full"
                             :terra_project        "$wfl-dev"
                             :extra                [:demux_deplete.spikein_db
                                                    :demux_deplete.samplesheets
                                                    :demux_deplete.sample_rename_map
                                                    :demux_deplete.bwaDbs
                                                    :demux_deplete.blastDbs
                                                    :gisaid_meta_prep.username
                                                    :gisaid_meta_prep.submitting_lab_addr
                                                    :package_genbank_ftp_submission.spuid_namespace
                                                    :gisaid_meta_prep.submitting_lab_name
                                                    :package_genbank_ftp_submission.author_template_sbt
                                                    :package_genbank_ftp_submission.account_name]}]
          (-> (->> (workflows/get-files inputs-type inputs)
                   (datasets/ingest-files tdr-profile source unique-prefix))
              (replace-urls-with-file-ids inputs-type inputs)
              (datasets/rename-gather outputmap)
              (json/write-str :escape-slash false)
              (storage/upload-content table-url))
          (let [{:keys [bad_row_count row_count]}
                (datarepo/poll-job
                 (datarepo/ingest-table source table-url table-name))]
            (is (= 1 row_count))
            (is (= 0 bad_row_count))))
        ;; At this point, workflow-launcher should run the workflow. The code
        ;; below simulates this effect.
        (let [outputs       (workflows/read-resource "sarscov2_illumina_full/outputs")
              outputs-type  (-> "sarscov2_illumina_full/description"
                                workflows/read-resource
                                :outputs
                                workflows/make-object-type)
              table-name    "sarscov2_illumina_full_outputs"
              unique-prefix (UUID/randomUUID)
              table-url     (str temp "outputs.json")]
          (-> (->> (workflows/get-files outputs-type outputs)
                   (datasets/ingest-files tdr-profile sink unique-prefix))
              (replace-urls-with-file-ids outputs-type outputs)
              (json/write-str :escape-slash false)
              (storage/upload-content table-url))
          (let [{:keys [bad_row_count row_count]}
                (datarepo/poll-job
                 (datarepo/ingest-table sink table-url table-name))]
            (is (= 1 row_count))
            (is (= 0 bad_row_count))))
        ;; TODO: verify the outputs have been written to TDR
        ))))

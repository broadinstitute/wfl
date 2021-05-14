(ns wfl.system.automation-test
  (:require [clojure.data.json          :as json]
            [clojure.test               :refer [deftest is]]
            [wfl.environment            :as env]
            [wfl.module.covid           :as covid]
            [wfl.service.datarepo       :as datarepo]
            [wfl.service.google.storage :as storage]
            [wfl.tools.datasets         :as datasets]
            [wfl.tools.fixtures         :as fixtures]
            [wfl.tools.resources        :as resources]
            [wfl.tools.workflows        :as workflows])
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
      [(fixtures/with-temporary-cloud-storage-folder fixtures/gcs-test-bucket)
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
        (let [;; This defines how we'll convert the inputs of the pipeline into
              ;; a form that can be ingested as a new row in the dataset.
              ;; I think a user would specify something like this in the initial
              ;; workload request, one mapping for dataset to workspace entity
              ;; names and one for outputs to dataset.
              from-inputs   (resources/read-resource "sarscov2_illumina_full/dataset-from-inputs.edn")
              inputs        (-> (resources/read-resource "sarscov2_illumina_full/inputs.edn")
                                (select-keys (map keyword (vals from-inputs))))
              inputs-type   (-> "sarscov2_illumina_full.edn"
                                resources/read-resource
                                :inputs
                                workflows/make-object-type)
              table-name    "flowcell"
              unique-prefix (UUID/randomUUID)
              table-url     (str temp "inputs.json")]
          (-> (->> (workflows/get-files inputs-type inputs)
                   (datasets/ingest-files tdr-profile source unique-prefix))
              (replace-urls-with-file-ids inputs-type inputs)
              (covid/rename-gather from-inputs)
              (json/write-str :escape-slash false)
              (storage/upload-content table-url))
          (let [{:keys [bad_row_count row_count]}
                (datarepo/poll-job
                 (datarepo/ingest-table source table-url table-name))]
            (is (= 1 row_count))
            (is (= 0 bad_row_count))))
        ;; At this point, workflow-launcher should run the workflow. The code
        ;; below simulates this effect.
        (let [outputs       (resources/read-resource "sarscov2_illumina_full/outputs.edn")
              outputs-type  (-> "sarscov2_illumina_full.edn"
                                resources/read-resource
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

(ns wfl.system.automation-test
  (:require [clojure.test :refer [deftest is]]
            [wfl.tools.datasets :as datasets]
            [wfl.tools.fixtures :as fixtures]
            [wfl.tools.workflows :as workflows]
            [wfl.environment :as env]
            [wfl.service.google.storage :as storage]
            [wfl.service.datarepo :as datarepo]
            [clojure.data.json :as json])
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
  (let [tdr-profile (env/getenv "WFL_TERRA_DATA_REPO_DEFAULT_PROFILE")
        tdr-sa      (env/getenv "WFL_TERRA_DATA_REPO_SA")]
    (fixtures/with-fixtures
      [(fixtures/with-temporary-cloud-storage-folder fixtures/gcs-test-bucket)
       (fixtures/with-temporary-dataset (datasets/unique-dataset-request "sarscov2-illumina-full-inputs.json"))
       (fixtures/with-temporary-dataset (datasets/unique-dataset-request "sarscov2-illumina-full-outputs.json"))]
      (fn [[temp source sink]]
        (let [inputs          (workflows/read-resource "sarscov2_illumina_full/inputs")
              inputs-type     (-> "sarscov2_illumina_full/description" workflows/read-resource :inputs workflows/make-object-type)
              table-name      "sarscov2_illumina_full_inputs"
              unique-prefix   (UUID/randomUUID)
              table-url       (str temp "inputs.json")]
          (-> (->> inputs
                   (workflows/get-files inputs-type)
                   (map storage/parse-gs-url)
                   (datasets/ingest-files unique-prefix source tdr-profile))
              (replace-urls-with-file-ids inputs-type inputs)
              (json/write-str :escape-slash false)
              (storage/upload-content table-url))
          (storage/add-object-reader tdr-sa table-url)
          (let [{:keys [bad_row_count row_count]}
                (datarepo/poll-job
                 (datarepo/ingest-table source table-url table-name))]
            (is (= 1 row_count))
            (is (= 0 bad_row_count))))
      ;; at this point, workflow-launcher should eventually run the workflow
        (let [outputs          (workflows/read-resource "sarscov2_illumina_full/outputs")
              outputs-type     (-> "sarscov2_illumina_full/description" workflows/read-resource :outputs workflows/make-object-type)
              table-name       "sarscov2_illumina_full_outputs"
              unique-prefix    (UUID/randomUUID)
              table-url     (str temp "outputs.json")]
          (-> (->> outputs
                   (workflows/get-files outputs-type)
                   (map storage/parse-gs-url)
                   (datasets/ingest-files unique-prefix sink tdr-profile))
              (replace-urls-with-file-ids outputs-type outputs)
              (json/write-str :escape-slash false)
              (storage/upload-content table-url))
          (storage/add-object-reader tdr-sa table-url)
          (let [{:keys [bad_row_count row_count]}
                (datarepo/poll-job
                 (datarepo/ingest-table sink table-url table-name))]
            (is (= 1 row_count))
            (is (= 0 bad_row_count))))))))

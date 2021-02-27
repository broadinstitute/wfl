(ns wfl.integration.datarepo-test
  (:require [clojure.data.json          :as json]
            [clojure.test               :refer [deftest is testing]]
            [wfl.environment            :as env]
            [wfl.service.datarepo       :as datarepo]
            [wfl.service.google.storage :as gcs]
            [wfl.tools.datasets         :as datasets]
            [wfl.tools.fixtures         :as fixtures]
            [wfl.tools.workflows        :as workflows]
            [wfl.util                   :as util])
  (:import [java.util UUID]))

(deftest test-create-dataset
  ;; To test that your dataset json file is valid, add its path to the list!
  (doseq [definition ["assemble-refbased-outputs.json"
                      "sarscov2-illumina-full-inputs.json"
                      "sarscov2-illumina-full-outputs.json"]]
    (testing (str "creating dataset " (util/basename definition))
      (fixtures/with-temporary-dataset (datasets/unique-dataset-request definition)
        #(let [dataset (datarepo/dataset %)]
           (is (= % (:id dataset))))))))

(defn ^:private replace-urls-with-file-ids
  [file->fileid type value]
  (-> (fn [type value]
        (case type
          ("Boolean" "Float" "Int" "Number" "String") value
          "File"                                      (file->fileid value)
          (throw (ex-info "Unknown type" {:type type :value value}))))
      (workflows/traverse type value)))

(deftest test-ingest-workflow-outputs
  (let [dataset-json     "assemble-refbased-outputs.json"
        profile          (env/getenv "WFL_TERRA_DATA_REPO_DEFAULT_PROFILE")
        pipeline-outputs (workflows/read-resource "assemble_refbased/outputs")
        outputs-type     (-> "assemble_refbased/description"
                             workflows/read-resource
                             :outputs
                             workflows/make-object-type)
        rename-gather    identity ;; collect and map outputs onto dataset names
        table-name       "assemble_refbased_outputs"
        workflow-id      (UUID/randomUUID)
        tdr-sa           (env/getenv "WFL_TERRA_DATA_REPO_SA")]
    (fixtures/with-fixtures
      [(fixtures/with-temporary-cloud-storage-folder fixtures/gcs-test-bucket)
       (fixtures/with-temporary-dataset (datasets/unique-dataset-request dataset-json))]
      (fn [[url dataset-id]]
        (let [table-url     (str url "table.json")]
          (-> (->> (workflows/get-files outputs-type pipeline-outputs)
                   (datasets/ingest-files workflow-id dataset-id profile))
              (replace-urls-with-file-ids outputs-type pipeline-outputs)
              rename-gather
              (json/write-str :escape-slash false)
              (gcs/upload-content table-url))
          (gcs/add-object-reader tdr-sa table-url)
          (let [{:keys [bad_row_count row_count]}
                (datarepo/poll-job
                 (datarepo/ingest-table dataset-id table-url table-name))]
            (is (= 1 row_count))
            (is (= 0 bad_row_count))))))))

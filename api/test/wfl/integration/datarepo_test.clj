(ns wfl.integration.datarepo-test
  (:require [clojure.data.json :as json]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [wfl.environment :as env]
            [wfl.service.datarepo :as datarepo]
            [wfl.service.google.storage :as gcs]
            [wfl.tools.fixtures :as fixtures]
            [wfl.tools.workflows :as workflows]
            [wfl.util :as util])
  (:import [java.util UUID]))

;; UUIDs known to the Data Repo.
;;
(def dataset "f359303e-15d7-4cd8-a4c7-c50499c90252")
(def profile "390e7a85-d47f-4531-b612-165fc977d3bd")

(defn ^:private make-dataset-request [dataset-basename]
  (-> (str "test/resources/datasets/" dataset-basename)
      slurp
      json/read-str
    ;; give it a unique name to avoid collisions with other tests
      (update "name" #(str % (-> (UUID/randomUUID) (str/replace "-" ""))))
      (update "defaultProfileId" (constantly profile))))

(deftest test-create-dataset
  ;; To test that your dataset json file is valid, add its path to the list!
  (doseq [definition ["assemble-refbased-outputs.json"
                      "sarscov2-illumina-full-inputs.json"
                      "sarscov2-illumina-full-outputs.json"]]
    (testing (str "creating dataset " (util/basename definition))
      (fixtures/with-temporary-dataset (make-dataset-request definition)
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

(defn ^:private ingest-files [workflow-id dataset-id profile-id bkt-obj-pairs]
  (letfn [(target-name  [obj]     (str/join "/" ["" workflow-id obj]))
          (mk-url       [bkt obj] (format "gs://%s/%s" bkt obj))
          (ingest-batch [batch]
            (->> (for [[bkt obj] batch] [(mk-url bkt obj) (target-name obj)])
                 (datarepo/bulk-ingest dataset-id profile-id)))]
    (->> bkt-obj-pairs
         (split-at 1000) ;; muscles says this is "probably fine"
         (mapv ingest-batch)
         (mapcat #(-> % datarepo/poll-job :loadFileResults)))))

(deftest test-ingest-workflow-outputs
  (let [dataset-json     "assemble-refbased-outputs.json"
        pipeline-outputs (workflows/read-resource "assemble-refbased-outputs")
        outputs-type     (-> "assemble-refbased-description"
                             workflows/read-resource
                             :outputs
                             workflows/make-object-type)
        rename-gather    identity                           ;; collect and map outputs onto dataset names
        table-name       "assemble_refbased_outputs"
        workflow-id      (UUID/randomUUID)
        tdr-sa           (env/getenv "WFL_DATA_REPO_SA")]
    (fixtures/with-fixtures
      [(fixtures/with-temporary-cloud-storage-folder fixtures/gcs-test-bucket)
       (fixtures/with-temporary-dataset (make-dataset-request dataset-json))]
      (fn [[url dataset-id]]
        (let [bkt-obj-pairs (map
                              gcs/parse-gs-url
                              (set (workflows/get-files outputs-type pipeline-outputs)))
              table-url     (str url "table.json")]
          (run!
           (partial gcs/add-storage-object-viewer tdr-sa)
           (into #{} (map first bkt-obj-pairs)))
          (-> (->> (ingest-files workflow-id dataset-id profile bkt-obj-pairs)
                   (map #(mapv % [:sourcePath :fileId]))
                   (into {}))
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

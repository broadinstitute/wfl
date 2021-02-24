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

(defn ^:private traverse
  "Traverse the `Traversable` data-types in `type`, calling `f` with the
   `typeName` and `value` of non-traversable types."
  [f type object]
  (letfn [(make-type-environment [{:keys [objectFieldNames]}]
            (into {}
                  (for [{:keys [fieldName fieldType]} objectFieldNames]
                    {(keyword fieldName) fieldType})))]
    ((fn go [type value]
       (case (:typeName type)
         "Array"
         (let [array-type (:arrayType type)]
           (map #(go array-type %) value))
         "Object"
         (let [name->type (make-type-environment type)]
           (into {} (map (fn [[k v]] [k (go (name->type k) v)]) value)))
         "Optional"
         (when value (go (:optionalType type) value))
         (f (:typeName type) value)))
     type object)))

(defn ^:private update-files-with-file-ids
  [file->fileid type value]
  (-> (fn [type value]
        (case type
          ("Boolean" "Float" "Int" "Number" "String") value
          "File"                                      (file->fileid value)
          (throw (ex-info "Unknown type" {:type  type :value value}))))
      (traverse type value)))

(defn ^:private get-files [type value]
  (letfn [(f [type object] (if (= "File" type) [object] []))]
    (flatten (vals (traverse f type value)))))

(defn ^:private unique-buckets [objects]
  (into #{} (map first objects)))

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
        (let [files     (map gcs/parse-gs-url
                             (set (get-files outputs-type pipeline-outputs)))
              table-url (str url "table.json")
              target    (fn [obj] (str/join "/" ["" workflow-id obj]))]
          (run!
           (partial gcs/add-storage-object-viewer tdr-sa)
           (unique-buckets files))
          (-> (->> files
                   (split-at 1000)
                   (mapv (fn [files]
                           (datarepo/bulk-ingest
                            dataset-id
                            profile
                            (for [[bkt obj] files]
                              [(format "gs://%s/%s" bkt obj) (target obj)]))))
                   (mapcat #(-> % datarepo/poll-job :loadFileResults))
                   (map (fn [{:keys [sourcePath fileId]}] {sourcePath fileId}))
                   (into {}))
              (update-files-with-file-ids outputs-type pipeline-outputs)
              rename-gather
              (json/write-str :escape-slash false)
              (gcs/upload-content table-url))
          (gcs/add-object-reader tdr-sa table-url)
          (let [{:keys [bad_row_count row_count]}
                (datarepo/poll-job
                 (datarepo/ingest-table dataset-id table-url table-name))]
            (is (= 1 row_count))
            (is (= 0 bad_row_count))))))))

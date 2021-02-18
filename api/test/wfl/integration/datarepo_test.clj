(ns wfl.integration.datarepo-test
  (:require [clojure.data.json          :as json]
            [clojure.string             :as str]
            [clojure.test               :refer [deftest is testing]]
            [wfl.environment            :as env]
            [wfl.service.datarepo       :as datarepo]
            [wfl.service.google.storage :as gcs]
            [wfl.tools.fixtures         :as fixtures]
            [wfl.tools.workflows        :as workflows]
            [wfl.util                   :as util])
  (:import [java.util UUID]))

;; UUIDs known to the Data Repo.
;;
(def dataset "f359303e-15d7-4cd8-a4c7-c50499c90252")
(def profile "390e7a85-d47f-4531-b612-165fc977d3bd")

(def ^:private assemble-refbased-outputs-dataset
  "test/resources/datasets/assemble-refbased-outputs.json")

(defn ^:private make-dataset-request [dataset-json-path]
  (-> (slurp dataset-json-path)
      json/read-str
    ;; give it a unique name to avoid collisions with other tests
      (update "name" #(str % (-> (UUID/randomUUID) (str/replace "-" ""))))
      (update "defaultProfileId" (constantly profile))))

(deftest test-create-dataset
  ;; To test that your dataset json file is valid, add its path to the list!
  (doseq [definition [assemble-refbased-outputs-dataset]]
    (testing (str "creating dataset " (util/basename definition))
      (fixtures/with-temporary-dataset (make-dataset-request definition)
        #(let [dataset (datarepo/dataset %)]
           (is (= % (:id dataset))))))))

(defn ^:private run-thunk! [x] (x))

(defn ^:private ingest!
  "Ingest `value` into the dataset specified by `dataset-id` depending on
   the `value`'s WDL `type` and return a thunk that performs any delayed work
   and returns an ingest-able value."
  [workload-id dataset-id profile-id type value]
  ;; Ingesting objects into TDR is asynchronous and must be done in two steps:
  ;; - Issue an "ingest" request for that object to TDR
  ;; - Poll the result of the request for the subsequent resource identifier
  ;;
  ;; Assuming TDR can fulfil ingest requests in parallel, we can (in theory)
  ;; increase throughput by issuing all ingest requests up front and then
  ;; poll for the resource identifiers later.
  ;;
  ;; To do this, this function returns a thunk that when run, performs
  ;; any delayed work needed to ingest an object of that data type (such as
  ;; polling for a file resource identifier) and returns a value that can
  ;; be ingested into a dataset table.
  (let [sequence      (fn [xs] #(mapv run-thunk! xs))
        sequence-vals (fn [m]  #(util/map-vals run-thunk! m))
        return        (fn [x]   (constantly x))
        bind          (fn [f g] (comp g f))
        ingest-file   (partial datarepo/ingest-file dataset-id profile-id)
        type-env      (fn [type]
                        (->> (:objectFieldNames type)
                             (map #(-> {(-> % :fieldName keyword) (:fieldType %)}))
                             (into {})))]
    ((fn go [type value]
       (case (:typeName type)
         "Array"
         (letfn [(go-elem [x] (go (:arrayType type) x))]
           ;; eagerly issue ingest requests for each element in the array
           (sequence (mapv go-elem value)))
         ("Boolean" "Float" "Int" "Number" "String")
         (return value)
         "File"
         (let [[bkt obj] (gcs/parse-gs-url value)
               target    (str/join "/" ["" workload-id obj])]
           (-> (env/getenv "WFL_DATA_REPO_SA")
               (gcs/add-object-reader bkt obj))
           (-> (ingest-file value target)
               return
               (bind (comp :fileId datarepo/poll-job))))
         "Object"
         (let [name->type (type-env type)]
           (sequence-vals
            (mapv (fn [[k v]] [k (go (name->type k) v)]) value)))
         "Optional"
         (if value
           (go (:optionalType type) value)
           (return nil))
         (throw (ex-info "No method to ingest type" {:typeName type}))))
     type value)))

(deftest test-ingest-workflow-outputs
  (let [dataset-json     assemble-refbased-outputs-dataset
        pipeline-outputs (workflows/read-resource "assemble-refbased-outputs")
        outputs-type     (-> "assemble-refbased-description"
                             workflows/read-resource
                             :outputs
                             workflows/make-object-type)
        rename-gather    identity ;; collect and map outputs onto dataset names
        table-name       "assemble_refbased_outputs"
        workflow-id      (UUID/randomUUID)]
    (fixtures/with-fixtures
      [(fixtures/with-temporary-cloud-storage-folder fixtures/gcs-test-bucket)
       (fixtures/with-temporary-dataset (make-dataset-request dataset-json))]
      (fn [[url dataset-id]]
        (let [table-url (str url "table.json")]
          (-> pipeline-outputs
              (->> (ingest! workflow-id dataset-id profile outputs-type))
              run-thunk!
              rename-gather
              (json/write-str :escape-slash false)
              (gcs/upload-content table-url))
          (gcs/add-object-reader (env/getenv "WFL_DATA_REPO_SA") table-url)
          (let [{:keys [bad_row_count row_count]}
                (datarepo/poll-job
                 (datarepo/ingest-table dataset-id table-url table-name))]
            (is (= 1 row_count))
            (is (= 0 bad_row_count))))))))

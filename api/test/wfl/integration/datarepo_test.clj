(ns wfl.integration.datarepo-test
  (:require [clojure.data.json          :as json]
            [clojure.test               :refer [deftest is testing]]
            [wfl.environment            :as env]
            [wfl.service.datarepo       :as datarepo]
            [wfl.service.firecloud      :as firecloud]
            [wfl.service.google.storage :as gcs]
            [wfl.tools.datasets         :as datasets]
            [wfl.tools.fixtures         :as fixtures]
            [wfl.tools.workflows        :as workflows]
            [wfl.util                   :as util])
  (:import [java.util UUID]))

(deftest test-create-dataset
  ;; To test that your dataset json file is valid, add its path to the list!
  (let [tdr-profile (env/getenv "WFL_TDR_DEFAULT_PROFILE")]
    (doseq [definition ["sarscov2-illumina-full-inputs.json"
                        "sarscov2-illumina-full-outputs.json"
                        "testing-dataset.json"]]
      (testing (str "creating dataset " (util/basename definition))
        (fixtures/with-temporary-dataset
          (datasets/unique-dataset-request tdr-profile definition)
          #(let [dataset (datarepo/dataset %)]
             (is (= % (:id dataset)))))))))

(defn ^:private replace-urls-with-file-ids
  [file->fileid type value]
  (-> (fn [type value]
        (case type
          ("Boolean" "Float" "Int" "Number" "String") value
          "File"                                      (file->fileid value)
          (throw (ex-info "Unknown type" {:type type :value value}))))
      (workflows/traverse type value)))

(def ^:private pi (* 4 (Math/atan 1)))

(def ^:private primitive-outputs
  {:outbool   true
   :outfile   "gs://broad-gotc-dev-wfl-ptc-test-inputs/external-reprocessing/exome/develop/not-a-real.unmapped.bam"
   :outfloat  pi
   :outint    27
   :outstring "Hello, World!"})

(def ^:private from-primitive-outputs
  {:boolean "outbool"
   :fileref "outfile"
   :float   "outfloat"
   :integer "outint"
   :string  "outstring"})

(def ^:private compound-outputs
  {:outarray    ["foo" "bar" "baz"]
   :outmap      {"bam" "gs://broad-gotc-dev-wfl-ptc-test-inputs/external-reprocessing/exome/develop/not-a-real.unmapped.bam"}
   :outoptional nil
   :outpair     [3, pi]
   :outstruct   {:value 5}})

(def ^:private from-compound-outputs
  {:strings "outarray"
   :floats  "inpair"
   :fileref "outoptional"
   :string  {:struct "outstruct" :map "outmap"}})

(deftest test-ingest-pipeline-outputs
  (let [dataset-json "testing-dataset.json"
        table-name   "parameters"
        tdr-profile  (env/getenv "WFL_TDR_DEFAULT_PROFILE")]
    (fixtures/with-fixtures
      [(fixtures/with-temporary-cloud-storage-folder
         "broad-gotc-dev-wfl-ptc-test-inputs")
       (fixtures/with-temporary-dataset
         (datasets/unique-dataset-request tdr-profile dataset-json))]
      (fn [[temp dataset]]
        (doseq [[outputs from-outputs outputs-type workflow-id]
                [[primitive-outputs
                  from-primitive-outputs
                  (-> (slurp "test/resources/workflows/primitive.wdl")
                      firecloud/describe-workflow
                      :outputs
                      workflows/make-object-type)
                  (UUID/randomUUID)]
                 [compound-outputs
                  from-compound-outputs
                  (-> (slurp "test/resources/workflows/compound.wdl")
                      firecloud/describe-workflow
                      :outputs
                      workflows/make-object-type)
                  (UUID/randomUUID)]]]
          (let [table-url (str temp workflow-id "/table.json")]
            (-> (->> (workflows/get-files outputs-type outputs)
                     (datasets/ingest-files tdr-profile dataset workflow-id))
                (replace-urls-with-file-ids outputs-type outputs)
                (datasets/rename-gather from-outputs)
                (json/write-str :escape-slash false)
                (gcs/upload-content table-url))
            (let [{:keys [bad_row_count row_count]}
                  (datarepo/poll-job
                   (datarepo/ingest-table dataset table-url table-name))]
              (is (= 1 row_count))
              (is (= 0 bad_row_count)))))))))

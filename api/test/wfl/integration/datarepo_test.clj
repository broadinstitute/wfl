(ns wfl.integration.datarepo-test
  (:require [clojure.data.json           :as json]
            [clojure.string              :as str]
            [clojure.test                :refer [deftest is testing]]
            [wfl.environment             :as env]
            [wfl.service.datarepo        :as datarepo]
            [wfl.service.firecloud       :as firecloud]
            [wfl.service.google.bigquery :as bigquery]
            [wfl.service.google.storage  :as gcs]
            [wfl.sink                    :as sink]
            [wfl.tools.datasets          :as datasets]
            [wfl.tools.fixtures          :as fixtures]
            [wfl.tools.resources         :as resources]
            [wfl.tools.snapshots         :as snapshots]
            [wfl.tools.workflows         :as workflows]
            [wfl.util                    :as util]))

(def ^:private testing-dataset {:id   "4a5d30fe-1f99-42cd-998b-a979885dea00"
                                :name "workflow_launcher_testing_dataset"})
(def ^:private testing-snapshot
  {:id "0ef4bc30-b8a0-4782-b178-e6145b777404"
   :name "workflow_launcher_testing_dataset7561609c9bb54ca6b34a12156dc947c1"})

;; Add a dataset JSON file to the `definition` list to test its validity
;; Wait 3 seconds to avoid random 404 transient issues from TDR.
;;
(deftest test-create-dataset
  (let [tdr-profile (env/getenv "WFL_TDR_DEFAULT_PROFILE")]
    (doseq [definition ["sarscov2-illumina-full-inputs.json"
                        "sarscov2-illumina-full-outputs.json"
                        "testing-dataset.json"]]
      (testing (str "creating dataset " (util/basename definition))
        (fixtures/with-temporary-dataset
          (datasets/unique-dataset-request tdr-profile definition)
          #(do (util/sleep-seconds 3)
               (let [dataset (datarepo/datasets %)]
                 (is (= % (:id dataset))))))))))

(def ^:private pi (* 4 (Math/atan 1)))

(def ^:private outputs
  {:outbool   true
   :outfile   "gs://broad-gotc-dev-wfl-ptc-test-inputs/external-reprocessing/exome/develop/not-a-real.unmapped.bam"
   :outfloat  pi
   :outint    27
   :outstring "Hello, World!"})

(def ^:private from-outputs
  {:boolean "outbool"
   :fileref "outfile"
   :float   "outfloat"
   :integer "outint"
   :string  "outstring"})

(deftest test-ingest-pipeline-outputs-and-snapshot
  (let [dataset-json "testing-dataset.json"
        table-name   "parameters"
        tdr-profile  (env/getenv "WFL_TDR_DEFAULT_PROFILE")]
    (fixtures/with-fixtures
      [(fixtures/with-temporary-cloud-storage-folder
         (env/getenv "WFL_TDR_TEMPORARY_STORAGE_BUCKET"))
       (fixtures/with-temporary-dataset
         (datasets/unique-dataset-request tdr-profile dataset-json))]
      (fn [[temp-bucket dataset-id]]
        (let [table-url        (str temp-bucket "table.json")
              workflow-id      (random-uuid)
              dataset          (datarepo/datasets dataset-id)
              [_bucket object] (gcs/parse-gs-url temp-bucket)]
          (-> (#'sink/rename-gather-bulk workflow-id
                                         dataset
                                         table-name
                                         outputs
                                         from-outputs
                                         (str "/" (util/de-slashify object)))
              (assoc :ingested (workflows/tdr-now))
              (json/write-str :escape-slash false)
              (gcs/upload-content table-url))
          (let [{:keys [bad_row_count row_count]}
                (datarepo/poll-job
                 (datarepo/ingest-table dataset-id table-url table-name))]
            (is (== 1 row_count))
            (is (== 0 bad_row_count))
            (let [row-ids
                  (-> dataset
                      (datarepo/query-table table-name [:datarepo_row_id])
                      :rows
                      flatten)]
              (is (== 1 (count row-ids))
                  "Single input row should have been written to the dataset")
              (testing "creating snapshot after completed ingest"
                (let [request         (snapshots/unique-snapshot-request
                                       tdr-profile
                                       dataset
                                       table-name
                                       row-ids)
                      snapshot-id     (datarepo/create-snapshot request)
                      snapshot        (datarepo/snapshot snapshot-id)
                      expected-prefix (str (:name dataset) "_" table-name)]
                  (is (= snapshot-id (:id snapshot)))
                  (is (str/starts-with? (:name snapshot) expected-prefix)
                      (str "Snapshot name should start with "
                           "dataset name and table name")))))))))))

(deftest test-flattened-query-result
  (let [samplesheets (-> (datarepo/snapshot (:id testing-snapshot))
                         (datarepo/query-table "flowcells" [:samplesheet_location])
                         :rows
                         (->> (mapcat first)))]
    (is (every? string? samplesheets) "Nested arrays were not normalized.")))

(defn ^:private table->map-view
  "Create a hash-map 'view' of (adapter for) the BigQuery `table`."
  [{:keys [schema rows] :as _table}]
  (let [make-entry (fn [idx field] [(-> field :name keyword) idx])
        key->index (into {} (map-indexed make-entry (:fields schema)))]
    (map (util/curry #(when-let [idx (key->index %2)] (%1 idx))) rows)))

(defn ^:private maps->table
  "Transform a list of maps into a table with `columns`."
  [maps columns]
  (let [table {:schema {:fields (mapv #(hash-map :name (name %)) columns)}}
        f     #(reduce (fn [row attr] (conj row (% attr))) [] columns)]
    (assoc table :rows (map f maps))))

(defn ^:private make-entity-import-request-tsv
  [from-dataset columns maps]
  (-> (map #(sink/rename-gather % from-dataset) maps)
      (maps->table columns)
      bigquery/dump-table->tsv))

(defn import-table
  "Import the BigQuery `table` into the `entity` in the Terra `workspace`,
   using `from-snapshot` to map column names in `table` to the `columns` in the
   workspace entity. Return the names of the entities imported into `workspace`"
  [table workspace [primary-key & _ :as columns] from-snapshot]
  (let [maps (table->map-view table)]
    (->> (make-entity-import-request-tsv from-snapshot columns maps)
         .getBytes
         (firecloud/import-entities workspace))
    (map #(% primary-key) maps)))

(def ^:private entity-columns
  "Return the columns in the `entity-type`."
  (comp #(mapv keyword %)
        #(apply cons %)
        (juxt :idName :attributeNames)))

(deftest test-import-snapshot
  (let [dataset-table "flowcells"
        entity        "flowcell"
        from-dataset  (resources/read-resource "entity-from-dataset.edn")
        columns       (-> "pathogen-genomic-surveillance/CDC_Viral_Sequencing_dev"
                          firecloud/list-entity-types :flowcell entity-columns)]
    (fixtures/with-temporary-workspace
      (fn [workspace]
        (let [entities (-> (datarepo/snapshot (:id testing-snapshot))
                           (datarepo/query-table dataset-table)
                           (import-table workspace columns from-dataset))
              names    (->> #(firecloud/list-entities workspace entity)
                            (comp not-empty)
                            util/poll
                            (map :name)
                            set)]
          (is (every? names entities)))))))

(deftest test-dataset-has-access-information
  (let [{:keys [datasetName projectId] :as bq-access}
        (-> (:id testing-dataset)
            datarepo/datasets
            (get-in [:accessInformation :bigQuery]))]
    (is bq-access "Fetched dataset should include BQ access information")
    (is (= "tdr-prod-workflow-launcher-dev" projectId)
        "Access information should include project ID")
    (is (= (str "datarepo_" (:name testing-dataset)) datasetName)
        "Access information should include appropriately prefixed dataset name")))

(deftest test-snapshot-has-access-information
  (let [{:keys [datasetName projectId] :as bq-access}
        (-> (:id testing-snapshot)
            datarepo/snapshot
            (get-in [:accessInformation :bigQuery]))]
    (is bq-access "Fetched snapshot should include BQ access information")
    (is (= "tdr-prod-workflow-launcher-dev" projectId)
        "Access information should include project ID")
    (is (= (:name testing-snapshot) datasetName)
        "Access information should include snapshot name")))

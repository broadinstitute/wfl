(ns wfl.integration.datarepo-test
  (:require [clojure.data.json           :as json]
            [clojure.string              :as str]
            [clojure.test                :refer [deftest is testing]]
            [wfl.environment             :as env]
            [wfl.service.datarepo        :as datarepo]
            [wfl.service.firecloud       :as firecloud]
            [wfl.service.google.storage  :as gcs]
            [wfl.service.google.bigquery :as bigquery]
            [wfl.sink                    :as sink]
            [wfl.tools.datasets          :as datasets]
            [wfl.tools.fixtures          :as fixtures]
            [wfl.tools.snapshots         :as snapshots]
            [wfl.tools.resources         :as resources]
            [wfl.tools.workflows         :as workflows]
            [wfl.util                    :as util :refer [>>>]])
  (:import [java.util UUID]))

(def ^:private testing-dataset {:id "4a5d30fe-1f99-42cd-998b-a979885dea00"
                                :name "workflow_launcher_testing_dataset"})
(def ^:private testing-snapshot
  {:id "0ef4bc30-b8a0-4782-b178-e6145b777404"
   :name "workflow_launcher_testing_dataset7561609c9bb54ca6b34a12156dc947c1"})

(deftest test-create-dataset
  ;; To test that your dataset json file is valid, add its path to the list!
  (let [tdr-profile (env/getenv "WFL_TDR_DEFAULT_PROFILE")]
    (doseq [definition ["sarscov2-illumina-full-inputs.json"
                        "sarscov2-illumina-full-outputs.json"
                        "testing-dataset.json"]]
      (testing (str "creating dataset " (util/basename definition))
        (fixtures/with-temporary-dataset
          (datasets/unique-dataset-request tdr-profile definition)
          ;; wait for 3 seconds to avoid random 404 transient issues from TDR
          #(do (util/sleep-seconds 3)
               (let [dataset (datarepo/datasets %)]
                 (is (= % (:id dataset))))))))))

(defn ^:private replace-urls-with-file-ids
  [file->fileid object]
  (letfn [(f [[type value]]
            (case type
              "File" (file->fileid value)
              value))]
    (workflows/traverse f object)))

(def ^:private pi (* 4 (Math/atan 1)))

(def ^:private outputs
  {:outbool   true
   :outfile   "gs://broad-gotc-dev-wfl-datarepo-outputs/test-inputs/external-reprocessing_exome_develop_not-a-real.unmapped.bam"
   :outfloat  pi
   :outint    27
   :outstring "Hello, World!"})

(def ^:private from-outputs
  {:boolean "outbool"
   :fileref "outfile"
   :float   "outfloat"
   :integer "outint"
   :string  "outstring"})

(deftest test-ingest-pipeline-outputs
  (let [dataset-json "testing-dataset.json"
        table-name   "parameters"
        tdr-profile  (env/getenv "WFL_TDR_DEFAULT_PROFILE")]
    (fixtures/with-fixtures
      [(fixtures/with-temporary-cloud-storage-folder fixtures/gcs-tdr-test-bucket)
       (fixtures/with-temporary-dataset
         (datasets/unique-dataset-request tdr-profile dataset-json))]
      (fn [[temp-bucket dataset-id]]
        (let [table-url (str temp-bucket "table.json")
              workflow-id (UUID/randomUUID)
              dataset (datarepo/datasets dataset-id)]
          (-> (sink/rename-gather-bulk workflow-id dataset table-name outputs from-outputs temp-bucket)
              (assoc :ingested (.format (util/utc-now) workflows/tdr-date-time-formatter))
              (json/write-str :escape-slash false)
              (gcs/upload-content table-url))
          (let [{:keys [bad_row_count row_count]}
                (datarepo/poll-job
                 (datarepo/ingest-table dataset-id table-url table-name))]
            (is (= 1 row_count))
            (is (= 0 bad_row_count))
            (is (seq (:rows (datarepo/query-table dataset table-name)))
                "inputs should have been written to the dataset")))))))

(deftest test-create-snapshot
  (let [tdr-profile (env/getenv "WFL_TDR_DEFAULT_PROFILE")
        dataset     (datarepo/datasets (:id testing-dataset))
        table       "flowcells"
        row-ids     (-> (datarepo/query-table-between
                         dataset
                         table
                         "run_date"
                         ["2021-04-30T04:00:00" "2021-05-01T04:00:00"]
                         [:datarepo_row_id])
                        :rows
                        flatten)]
    (fixtures/with-temporary-snapshot
      (snapshots/unique-snapshot-request tdr-profile dataset table row-ids)
      #(let [snapshot (datarepo/snapshot %)]
         (is (= % (:id snapshot)))
         (is (str/starts-with? (:name snapshot) (str (:name dataset) "_" table))
             "Snapshot name should start with dataset name and table name")))))

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
  (>>> (juxt :idName :attributeNames)
       #(apply cons %)
       #(mapv keyword %)))

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

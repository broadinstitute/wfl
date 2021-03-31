(ns wfl.integration.datarepo-test
  (:require [clojure.data.json           :as json]
            [clojure.test                :refer [deftest is testing]]
            [wfl.environment             :as env]
            [wfl.service.datarepo        :as datarepo]
            [wfl.service.firecloud       :as firecloud]
            [wfl.service.google.storage  :as gcs]
            [wfl.service.google.bigquery :as bigquery]
            [wfl.tools.datasets          :as datasets]
            [wfl.tools.fixtures          :as fixtures]
            [wfl.tools.snapshots         :as snapshots]
            [wfl.tools.resources         :as resources]
            [wfl.tools.workflows         :as workflows]
            [wfl.util                    :as util :refer [>>>]])
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

(deftest test-ingest-pipeline-outputs
  (let [dataset-json "testing-dataset.json"
        table-name   "parameters"
        tdr-profile  (env/getenv "WFL_TDR_DEFAULT_PROFILE")
        outputs-type (-> (resources/read-resource "primitive.edn")
                         :outputs
                         workflows/make-object-type)
        workflow-id  (UUID/randomUUID)]
    (fixtures/with-fixtures
      [(fixtures/with-temporary-cloud-storage-folder
         "broad-gotc-dev-wfl-ptc-test-inputs")
       (fixtures/with-temporary-dataset
         (datasets/unique-dataset-request tdr-profile dataset-json))]
      (fn [[temp dataset]]
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
            (is (= 0 bad_row_count))))))))

(def ^:private testing-dataset "ff6e2b40-6497-4340-8947-2f52a658f561")

;; Get row-ids from BigQuery and use them to create a snapshot.
(deftest test-create-snapshot
  (let [tdr-profile (env/getenv "WFL_TDR_DEFAULT_PROFILE")
        dataset     (datarepo/dataset testing-dataset)
        table       "flowcell"
        from        "2021-03-30"
        until       "2021-03-31"
        row-ids     (-> (datarepo/query-table-between
                         dataset
                         table
                         [from until]
                         [:datarepo_row_id])
                        :rows
                        flatten)]
    (testing "creating snapshot"
      (fixtures/with-temporary-snapshot
        (snapshots/unique-snapshot-request tdr-profile dataset table row-ids)
        #(let [snapshot (datarepo/snapshot %)]
           (is (= % (:id snapshot))))))))

(deftest test-flattened-query-result
  (let [samplesheets (-> (datarepo/dataset testing-dataset)
                         (datarepo/query-table "flowcell" [:samplesheets])
                         :rows
                         (->> (mapcat first)))]
    (is (every? string? samplesheets) "Nested arrays were not normalized.")))

(defn ^:private table->map-view
  "Create a hash-map 'view' of the BigQuery `table`. A 'view' is one"
  [{:keys [schema rows] :as _table}]
  (let [make-entry (fn [idx field] [(-> field :name keyword) idx])
        key->index (into {} (map-indexed make-entry (:fields schema)))]
    (map (util/curry #(when-let [idx (key->index %2)] (%1 idx))) rows)))

(defn ^:private maps->table
  "Transform a list of maps into a table with columns given by `attributes`."
  [maps attributes]
  (let [table {:schema {:fields (mapv #(-> {:name (name %)}) attributes)}}
        f     #(reduce (fn [row attr] (conj row (% attr))) [] attributes)]
    (assoc table :rows (map f maps))))

(defn ^:private make-entity-import-request-tsv
  [from-dataset primary-key maps]
  ;; the columns need to be ordered such that that primary key comes first
  (let [columns (>>> #(dissoc % primary-key) keys sort #(cons primary-key %))]
    (-> (map #(datasets/rename-gather % from-dataset) maps)
        (maps->table (columns from-dataset))
        bigquery/dump-table->tsv)))

(defn import-snapshot
  "Import the BigQuery table `snapshot` into the `entity` in the Terra
   `workspace`, using `from-snapshot` to map column names in `snapshot` to the
   names in the workspace `table`.
   Return `[entity name]` pairs of the entities imported into the workspace."
  [workspace entity snapshot from-snapshot]
  (let [primary-key (-> entity name (str "_id") keyword)
        maps        (table->map-view snapshot)]
    (->> (make-entity-import-request-tsv from-snapshot primary-key maps)
         .getBytes
         (firecloud/import-entities workspace))
    (map (comp #(-> [entity %]) #(% primary-key)) maps)))

;; not sure where this should live!
(def ^:private snapshot-id "7cb392d8-949b-419d-b40b-d039617d2fc7")
(deftest test-import-snapshot
  (let [dataset-table "flowcell"
        entity        "flowcell"
        from-dataset  (resources/read-resource "entity-from-dataset.edn")]
    (fixtures/with-temporary-workspace
      (fn [workspace]
        ((>>> datarepo/snapshot
              #(datarepo/query-table % dataset-table)
              #(import-snapshot workspace entity % from-dataset)
              (fn [entities]
                (try
                  (let [names (->> #(firecloud/list-entities workspace entity)
                                   (util/poll-while empty?)
                                   (map :name)
                                   set)]
                    (doseq [[_ name] entities] (is (contains? names name))))
                  (finally (firecloud/delete-entities workspace entities)))))
         snapshot-id)))))


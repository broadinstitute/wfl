(ns wfl.integration.source-test
  (:require [clojure.test          :refer [deftest is testing use-fixtures]]
            [clojure.java.jdbc     :as jdbc]
            [clojure.set           :as set]
            [clojure.spec.alpha    :as s]
            [wfl.debug]
            [wfl.service.datarepo  :as datarepo]
            [wfl.service.postgres  :as postgres]
            [wfl.source            :as source]
            [wfl.stage             :as stage]
            [wfl.tools.fixtures    :as fixtures]
            [wfl.util              :as util])
  (:import [java.time Instant LocalDateTime]
           [java.util UUID]
           [wfl.util  UserException]))

(def ^:private testing-dataset "cd25d59e-1451-44d0-8a24-7669edb9a8f8")
(def ^:private testing-snapshot "e8f1675e-1e7c-48b4-92ab-3598425c149d")
(def ^:private testing-table-name "flowcells")
(def ^:private testing-column-name "run_date")

;; Snapshot creation mock
(def ^:private mock-new-rows-size 1234)

(defn ^:private parse-timestamp
  "Parse `timestamp` string in BigQuery format."
  [timestamp]
  (LocalDateTime/parse timestamp @#'source/bigquery-datetime-format))

(defn ^:private mock-find-new-rows
  [_source interval]
  (is (every? parse-timestamp interval))
  (range mock-new-rows-size))

(defn ^:private mock-create-snapshots [_ _ row-ids]
  (letfn [(f [idx shard] [(vec shard) (format "mock_job_id_%s" idx)])]
    (->> (partition-all 500 row-ids)
         (map-indexed f))))

;; Note this mock only covers happy paths of TDR jobs
(defn ^:private mock-check-tdr-job [job-id]
  {:snapshot_id (str (UUID/randomUUID))
   :job_status  "succeeded"
   :id          job-id})

;; Queue mocks
(use-fixtures :once fixtures/temporary-postgresql-database)

(defn ^:private create-tdr-source []
  (jdbc/with-db-transaction [tx (postgres/wfl-db-config)]
    (->> {:column         "a-column"
          :dataset        {:name "a-dataset"}
          :id             "an-id"
          :name           "Terra DataRepo"
          :skipValidation true
          :table          "a-table"
          :type           "a-type"}
         (source/create-source! tx (rand-int 1000000))
         (zipmap [:source_type :source_items])
         (source/load-source! tx))))

(defn ^:private reload-source [tx {:keys [type id] :as _source}]
  (source/load-source! tx {:source_type type :source_items (str id)}))

;; A stable vector of TDR row IDs.
;;
(defonce all-rows
  (delay (vec (repeatedly mock-new-rows-size #(str (UUID/randomUUID))))))

(defn ^:private datarepo-query-table-between-all
  "Mock datarepo/query-table-between to find all rows."
  [_dataset _table _between interval columns]
  (is (every? parse-timestamp interval))
  (letfn [(field [column] {:mode "NULLABLE" :name column :type "STRING"})
          (fields [columns] (mapv field columns))]
    (-> {:jobComplete true
         :kind "bigquery#queryResponse"
         :rows [@all-rows]
         :totalRows (str (count @all-rows))}
        (assoc-in [:schema :fields] (fields columns)))))

(defn ^:private datarepo-query-table-between-miss
  "Mock datarepo/query-table-between to miss some rows."
  [dataset table between interval columns]
  (-> (datarepo-query-table-between-all dataset table between interval columns)
      (update-in [:rows 0] butlast)
      (assoc :totalRows (str (dec mock-new-rows-size)))))

(deftest test-backfill-tdr-source
  (letfn [(rows-from [source]
            (jdbc/with-db-transaction [tx (postgres/wfl-db-config)]
              (->> source :details
                   (postgres/get-table tx)
                   (mapcat :datarepo_row_ids))))
          (make-bigquery-timestamp []
            (-> (Instant/now) str (subs 0 (count "2021-07-14T15:47:02"))))
          (total-rows [query args]
            (-> query (apply args) :totalRows Integer/parseInt))
          (start-then-update-source [source]
            (with-redefs [datarepo/query-table-between datarepo-query-table-between-miss
                          source/check-tdr-job         mock-check-tdr-job
                          source/create-snapshots      mock-create-snapshots]
              (source/update-source!
               (jdbc/with-db-transaction [tx (postgres/wfl-db-config)]
                 (source/start-source! tx source)
                 (reload-source tx source)))))
          (update-source [source]
            (with-redefs [datarepo/query-table-between datarepo-query-table-between-all
                          source/check-tdr-job         mock-check-tdr-job
                          source/create-snapshots      mock-create-snapshots]
              (source/update-source!
               (jdbc/with-db-transaction [tx (postgres/wfl-db-config)]
                 (reload-source tx source)))))]
    (let [args         [testing-dataset testing-table-name testing-column-name
                        [(make-bigquery-timestamp) (make-bigquery-timestamp)]
                        [:datarepo_row_id]]
          record-count (int (Math/ceil (/ mock-new-rows-size 500)))
          source       (create-tdr-source)]
      (testing "update-source! loads snapshots"
        (start-then-update-source source)
        (let [miss-rows  (rows-from source)
              miss-count (dec mock-new-rows-size)
              miss-set   (set miss-rows)]
          (is (== record-count (stage/queue-length source)))
          (is (== miss-count   (total-rows datarepo-query-table-between-miss args)))
          (is (== miss-count   (count miss-set)))
          (testing "update-source! adds a snaphot of rows missed in prior interval"
            (update-source source)
            (let [all-rows (rows-from source)
                  all-set  (set all-rows)
                  missing  (set/difference all-set miss-set)]
              (wfl.debug/trace missing)
              (is (== (inc record-count) (stage/queue-length source)))
              (is (== mock-new-rows-size (total-rows datarepo-query-table-between-all args)))
              (is (=  (first missing) (last all-rows)))
              (wfl.debug/trace (first missing))
              (wfl.debug/trace (last all-rows))
              (is (== 1 (count missing))))))))))

(deftest test-create-tdr-source-from-valid-request
  (is (stage/validate-or-throw
       {:name    "Terra DataRepo"
        :dataset testing-dataset
        :table   testing-table-name
        :column  testing-column-name})))

(deftest test-create-tdr-source-with-non-existent-dataset
  (is (thrown-with-msg?
       UserException #"Cannot access dataset"
       (stage/validate-or-throw
        {:name    "Terra DataRepo"
         :dataset util/uuid-nil}))))

(deftest test-create-tdr-source-with-invalid-dataset-table
  (is (thrown-with-msg?
       UserException #"Table not found"
       (stage/validate-or-throw
        {:name    "Terra DataRepo"
         :dataset testing-dataset
         :table   "no_such_table"}))))

(deftest test-create-tdr-source-with-invalid-dataset-column
  (is (thrown-with-msg?
       UserException #"Column not found"
       (stage/validate-or-throw
        {:name    "Terra DataRepo"
         :dataset testing-dataset
         :table   testing-table-name
         :column  "no_such_column"}))))

(deftest test-start-tdr-source
  (let [source (create-tdr-source)]
    (is (-> source :last_checked nil?))
    (jdbc/with-db-transaction [tx (postgres/wfl-db-config)]
      (source/start-source! tx source)
      (is (:last_checked (reload-source tx source))
          ":last_checked was not updated"))))

(deftest test-update-tdr-source
  (let [source               (create-tdr-source)
        expected-num-records (int (Math/ceil (/ mock-new-rows-size 500)))]
    (with-redefs-fn
      {#'source/create-snapshots mock-create-snapshots
       #'source/find-new-rows    mock-find-new-rows
       #'source/check-tdr-job    mock-check-tdr-job}
      (fn []
        (source/update-source!
         (jdbc/with-db-transaction [tx (postgres/wfl-db-config)]
           (source/start-source! tx source)
           (reload-source tx source)))
        (is (== expected-num-records (stage/queue-length source))
            "snapshots should be enqueued")
        (jdbc/with-db-transaction [tx (postgres/wfl-db-config)]
          (let [records (->> source :details (postgres/get-table tx))]
            (letfn [(record-updated? [record]
                      (and (= "succeeded" (:snapshot_creation_job_status record))
                           (not (nil? (:snapshot_creation_job_id record)))
                           (not (nil? (:snapshot_id record)))))]
              (testing "source details got updated with correct number of snapshot jobs"
                (is (= expected-num-records (count records))))
              (testing "all snapshot jobs were updated and corresponding snapshot ids were inserted"
                (is (every? record-updated? records))))))
        (source/update-source!
         (jdbc/with-db-transaction [tx (postgres/wfl-db-config)]
           (source/stop-source! tx source)
           (reload-source tx source)))
        (is (== expected-num-records (stage/queue-length source))
            "no more snapshots should be enqueued")
        (is (not (stage/done? source)) "the tdr source was done before snapshots were consumed")))))

(deftest test-stop-tdr-sourced
  (let [source (create-tdr-source)]
    (jdbc/with-db-transaction [tx (postgres/wfl-db-config)]
      (source/start-source! tx source)
      (source/stop-source! tx source)
      (let [source (reload-source tx source)]
        (is (:stopped (reload-source tx source)) ":stopped was not written")))))

(defn ^:private create-tdr-snapshot-list [snapshots]
  (jdbc/with-db-transaction [tx (postgres/wfl-db-config)]
    (->> {:name           "TDR Snapshots"
          :snapshots      snapshots
          :skipValidation true}
         (source/create-source! tx (rand-int 1000000))
         (zipmap [:source_type :source_items])
         (source/load-source! tx))))

(deftest test-tdr-snapshot-list-to-edn
  (let [snapshot {:name "test-snapshot-name" :id (str (UUID/randomUUID))}
        source   (util/to-edn (create-tdr-snapshot-list [snapshot]))]
    (is (not-any? source [:id :type]))
    (is (= (:snapshots source) [(:id snapshot)]))
    (is (s/valid? ::source/snapshot-list-source source))))

(deftest test-create-covid-workload-with-empty-snapshot-list
  (is (stage/validate-or-throw
       {:name      "TDR Snapshots"
        :snapshots [testing-snapshot]})))

(deftest test-create-covid-workload-with-invalid-snapshot
  (is (thrown-with-msg?
       UserException #"Cannot access snapshot"
       (stage/validate-or-throw
        {:name     "TDR Snapshots"
         :snapshots [util/uuid-nil]}))))

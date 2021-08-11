(ns wfl.integration.source-test
  (:require [clojure.java.jdbc    :as jdbc]
            [clojure.spec.alpha   :as s]
            [clojure.test         :refer [deftest is testing use-fixtures]]
            [wfl.service.postgres :as postgres]
            [wfl.stage            :as stage]
            [wfl.source           :as source]
            [wfl.tools.fixtures   :as fixtures]
            [wfl.util             :as util])
  (:import [java.time LocalDateTime]
           [java.util UUID]
           [wfl.util  UserException]))

(def ^:private testing-dataset "cd25d59e-1451-44d0-8a24-7669edb9a8f8")
(def ^:private testing-snapshot "e8f1675e-1e7c-48b4-92ab-3598425c149d")
(def ^:private testing-table-name "flowcells")
(def ^:private testing-column-name "run_date")

;; Snapshot creation mock
(def ^:private mock-new-rows-size 2021)
(defn ^:private mock-find-new-rows [_ interval]
  (is (every? #(LocalDateTime/parse % @#'source/bigquery-datetime-format) interval))
  (take mock-new-rows-size (range)))
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
    (->> {:name           "Terra DataRepo",
          :dataset        "this"
          :table          "is"
          :column         "fun"
          :skipValidation true}
         (source/create-source! tx (rand-int 1000000))
         (zipmap [:source_type :source_items])
         (source/load-source! tx))))

(defn ^:private reload-source [tx {:keys [type id] :as _source}]
  (source/load-source! tx {:source_type type :source_items (str id)}))

(deftest test-create-tdr-source-from-valid-request
  (is (source/validate-datarepo-source!
       {:name    "Terra DataRepo"
        :dataset testing-dataset
        :table   testing-table-name
        :column  testing-column-name})))

(deftest test-create-tdr-source-with-non-existent-dataset
  (is (thrown-with-msg?
       UserException #"Cannot access dataset"
       (source/validate-datarepo-source!
        {:name    "Terra DataRepo"
         :dataset util/uuid-nil}))))

(deftest test-create-tdr-source-with-invalid-dataset-table
  (is (thrown-with-msg?
       UserException #"Table not found"
       (source/validate-datarepo-source!
        {:name    "Terra DataRepo"
         :dataset testing-dataset
         :table   "no_such_table"}))))

(deftest test-create-tdr-source-with-invalid-dataset-column
  (is (thrown-with-msg?
       UserException #"Column not found"
       (source/validate-datarepo-source!
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
  (is (source/validate-tdr-snapshot-list
       {:name      "TDR Snapshots"
        :snapshots [testing-snapshot]})))

(deftest test-create-covid-workload-with-invalid-snapshot
  (is (thrown-with-msg?
       UserException #"Cannot access snapshot"
       (source/validate-tdr-snapshot-list
        {:name     "TDR Snapshots"
         :snapshots [util/uuid-nil]}))))

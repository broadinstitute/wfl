(ns wfl.integration.source-test
  (:require [wfl.source :as source]
            [clojure.test :refer :all]
            [wfl.stage :as stage]
            [clojure.java.jdbc :as jdbc]
            [wfl.service.postgres :as postgres]
            [wfl.util :as util]
            [clojure.spec.alpha :as s]
            [wfl.tools.fixtures :as fixtures])
  (:import [java.time LocalDateTime]
           [java.util UUID]))

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

(def ^:private testing-snapshot "e8f1675e-1e7c-48b4-92ab-3598425c149d")
(def ^:private testing-namespace "wfl-dev")
(def ^:private testing-table-name "flowcells")

;; Queue mocks
(use-fixtures :once
  (fixtures/temporary-environment {"WFL_TDR_URL" "https://data.terra.bio"})
  fixtures/temporary-postgresql-database)

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
    (is (s/valid? ::spec/snapshot-list-source source))))

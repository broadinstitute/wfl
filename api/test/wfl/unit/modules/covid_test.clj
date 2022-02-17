(ns wfl.unit.modules.covid-test
  (:require [clojure.spec.alpha   :as s]
            [clojure.test         :refer [deftest is testing]]
            [wfl.service.datarepo :as datarepo]
            [wfl.source           :as source]
            [wfl.tools.workloads  :as workloads])
  (:import [java.time OffsetDateTime ZoneId]
           [java.lang Math]))

(def ^:private readers-list ["hornet@firecloud.org"])

;; Snapshot creation mock
(defn mock-create-snapshot-job
  [snapshot-request]
  (is (= readers-list (:readers snapshot-request)))
  (-> snapshot-request
      (select-keys [:name])
      (assoc :id "mock-job-id")))

(deftest test-create-snapshots
  (let [mock-new-rows-size  2021
        expected-num-shards (int (Math/ceil (/ mock-new-rows-size 500)))
        workload            {:uuid   "a-workload-uuid"
                             :source {:dataset         {}
                                      :table           "flowcell"
                                      :snapshotReaders readers-list}}
        row-ids             (take mock-new-rows-size (range))
        now-obj             (OffsetDateTime/now (ZoneId/of "UTC"))
        shards->snapshot-requests
        (with-redefs-fn
          {#'datarepo/create-snapshot-job mock-create-snapshot-job}
          #(vec (#'source/create-snapshots workload now-obj row-ids)))]
    (testing "snapshot requests are properly partitioned and made unique"
      (is (= expected-num-shards (count shards->snapshot-requests))
          "requests are not partitioned correctly!")
      (is (apply distinct? (map #(get-in % [1 :name]) shards->snapshot-requests))
          "requests are not made unique!"))))

(deftest test-tdr-source-spec
  (let [valid?           (partial s/valid? ::source/tdr-source)
        {:keys [source]} (workloads/covid-workload-request)]
    (is (valid? source) (s/explain-str ::source/tdr-source source))
    (is (not (valid? (assoc source :snapshotReaders ["geoff"])))
        "snapshotReaders should be a list of email addresses")))

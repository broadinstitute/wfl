(ns wfl.unit.modules.covid-test
  (:require [clojure.spec.alpha   :as s]
            [clojure.test         :refer [deftest is testing]]
            [wfl.module.covid     :as covid]
            [wfl.module.all       :as all]
            [wfl.service.datarepo :as datarepo]
            [wfl.source           :as source]
            [wfl.tools.resources  :as resources]
            [wfl.tools.workloads  :as workloads])
  (:import [java.time OffsetDateTime ZoneId]
           [java.lang Math]))

(deftest test-rename-gather
  (let [inputs (resources/read-resource "sarscov2_illumina_full/inputs.edn")]
    (is (= {:workspace_name "SARSCoV2-Illumina-Full"}
           (covid/rename-gather inputs {:workspace_name "$SARSCoV2-Illumina-Full"})))
    (is (= {:instrument_model "Illumina NovaSeq 6000"}
           (covid/rename-gather inputs {:instrument_model "instrument_model"})))
    (is (= {:extra ["broad_gcid-srv"]}
           (covid/rename-gather inputs {:extra ["package_genbank_ftp_submission.account_name"]})))
    (is (= {:extra {:account_name "broad_gcid-srv" :workspace_name "SARSCoV2-Illumina-Full"}}
           (covid/rename-gather
            inputs
            {:extra {:account_name   "package_genbank_ftp_submission.account_name"
                     :workspace_name "$SARSCoV2-Illumina-Full"}})))))

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
        source              {:dataset {}
                             :table "flowcell"
                             :snapshotReaders readers-list}
        row-ids             (take mock-new-rows-size (range))
        now-obj             (OffsetDateTime/now (ZoneId/of "UTC"))
        shards->snapshot-requests
        (with-redefs-fn
          {#'datarepo/create-snapshot-job mock-create-snapshot-job}
          #(vec (#'source/create-snapshots source now-obj row-ids)))]
    (testing "snapshot requests are properly partitioned and made unique"
      (is (= expected-num-shards (count shards->snapshot-requests))
          "requests are not partitioned correctly!")
      (is (apply distinct? (map #(get-in % [1 :name]) shards->snapshot-requests))
          "requests are not made unique!"))))

(deftest test-tdr-source-spec
  (let [valid?           (partial s/valid? ::all/tdr-source)
        {:keys [source]} (workloads/covid-workload-request)]
    (is (valid? source) (s/explain-str ::all/tdr-source source))
    (is (not (valid? (assoc source :snapshotReaders ["geoff"])))
        "snapshotReaders should be a list of email addresses")))

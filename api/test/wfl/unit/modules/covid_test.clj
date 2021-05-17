(ns wfl.unit.modules.covid-test
  (:require [clojure.test        :refer :all]
            [wfl.module.covid    :as covid]
            [wfl.service.datarepo :as datarepo]
            [wfl.tools.resources :as resources])
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

;; Snapshot creation mock
(defn ^:private mock-create-snapshot-job
  [snapshot-request]
  {:id "mock-job-id"
   ;; inject the request for testing purposes
   :request snapshot-request})

(deftest test-create-snapshots
  (let [mock-new-rows-size 2021
        expected-num-shards (int (Math/ceil (/ mock-new-rows-size 500)))
        source {:dataset {}
                :dataset_table "flowcell"}
        row-ids (take mock-new-rows-size (range))
        now-obj (OffsetDateTime/now (ZoneId/of "UTC"))
        [shards snapshot-requests] (with-redefs-fn
                                     {#'datarepo/create-snapshot-job mock-create-snapshot-job}
                                     #(#'covid/create-snapshots source now-obj row-ids))]
    (testing "snapshot requests are properly partitioned and made unique"
      (is (= expected-num-shards (count shards)) "requests are not partitioned correctly!")
      (is (apply distinct? (map #(get-in % [:request :name]) snapshot-requests)) "requests are not made unique!"))))
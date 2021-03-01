(ns wfl.unit.datasets-test
  (:require [clojure.test :refer :all]
            [wfl.tools.workflows :as workflows]
            [wfl.tools.datasets :as datasets]))

(deftest test-rename-gather
  (let [inputs (workflows/read-resource "sarscov2_illumina_full/inputs")]
    (is (= {:workspace_name "SARSCoV2-Illumina-Full"}
           (datasets/rename-gather inputs {:workspace_name "$SARSCoV2-Illumina-Full"})))
    (is (= {:instrument_model "Illumina NovaSeq 6000"}
           (datasets/rename-gather inputs {:instrument_model :instrument_model})))
    (is (= {:extras "{\"package_genbank_ftp_submission.account_name\":\"broad_gcid-srv\"}"}
           (datasets/rename-gather inputs {:extras [:package_genbank_ftp_submission.account_name]})))))

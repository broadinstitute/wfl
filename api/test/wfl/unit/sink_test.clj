(ns wfl.unit.sink-test
  (:require [clojure.test         :refer [deftest is]]
            [wfl.sink             :as sink]
            [wfl.tools.resources  :as resources]))

(deftest test-rename-gather
  (let [inputs (resources/read-resource "sarscov2_illumina_full/inputs.edn")]
    (is (= {:workspace_name "SARSCoV2-Illumina-Full"}
           (sink/rename-gather inputs {:workspace_name "$SARSCoV2-Illumina-Full"})))
    (is (= {:instrument_model "Illumina NovaSeq 6000"}
           (sink/rename-gather inputs {:instrument_model "instrument_model"})))
    (is (= {:extra ["broad_gcid-srv"]}
           (sink/rename-gather inputs {:extra ["package_genbank_ftp_submission.account_name"]})))
    (is (= {:extra {:account_name "broad_gcid-srv" :workspace_name "SARSCoV2-Illumina-Full"}}
           (sink/rename-gather
            inputs
            {:extra {:account_name   "package_genbank_ftp_submission.account_name"
                     :workspace_name "$SARSCoV2-Illumina-Full"}})))))

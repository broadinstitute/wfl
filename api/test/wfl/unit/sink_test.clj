(ns wfl.unit.sink-test
  (:require [clojure.test         :refer [deftest is]]
            [wfl.sink             :as sink]
            [wfl.tools.endpoints  :refer [coercion-tester]]
            [wfl.tools.resources  :as resources]
            [wfl.util             :refer [uuid-nil]]))

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

(deftest test-rename-gather-bulk
  (let [inputs (resources/read-resource "sarscov2_illumina_full/inputs.edn")
        dataset (resources/read-resource "sarscov2-illumina-full-inputs.json")
        result (sink/rename-gather-bulk inputs {:flowcell_tgz "flowcell_tgz"
                                                :flowcell_id "flowcell_id"} "flowcell" dataset)]
    (is (= (:flowcell_id inputs)
           (:flowcell_id result)))
    (is (= (:flowcell_tgz inputs)
           (-> result :flowcell_tgz :sourcePath)))))

(def ^:private workspace-sink-request
  {:name        @#'sink/terra-workspace-sink-name
   :workspace   "namespace/name"
   :entityType  "tablename"
   :identifier  "sample_id"
   :fromOutputs {}})

(def ^:private datarepo-sink-request
  {:name        @#'sink/datarepo-sink-name
   :dataset     (str uuid-nil)
   :table       "tablename"
   :fromOutputs {}})

(deftest test-workspace-sink-request-coercion
  (let [test! (coercion-tester ::sink/sink)]
    (test! workspace-sink-request)))

(deftest test-datarepo-sink-request-coercion
  (let [test! (coercion-tester ::sink/sink)]
    (test! datarepo-sink-request)))

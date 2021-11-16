(ns wfl.unit.sink-test
  (:require [clojure.test         :refer [deftest is]]
            [wfl.sink             :as sink]
            [wfl.tools.endpoints  :refer [coercion-tester]]
            [wfl.tools.resources  :as resources]
            [wfl.util             :refer [uuid-nil]])
  (:import [java.util UUID]
           [clojure.lang ExceptionInfo]))

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
        result (sink/rename-gather-bulk (UUID/randomUUID) dataset "flowcell" inputs {:flowcell_tgz "flowcell_tgz"
                                                                                     :flowcell_id "flowcell_id"})]
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

(deftest test-throw-or-entity-name-from-workspace
  (let [inputs   {:a "aInput" :b "bInput"}
        outputs  {:b "bOutput" :c "cOutput"}
        workflow {:inputs inputs :outputs outputs}
        msg      (re-pattern sink/entity-name-not-found-error-message)]
    (is (thrown-with-msg?
         ExceptionInfo msg
         (#'sink/throw-or-entity-name-from-workflow nil {:identifier "a"}))
        "When nil workflow, should throw")
    (is (thrown-with-msg?
         ExceptionInfo msg
         (#'sink/throw-or-entity-name-from-workflow
          (select-keys workflow [:inputs]) {:identifier "c"}))
        "When no outputs, should only check inputs")
    (is (thrown-with-msg?
         ExceptionInfo msg
         (#'sink/throw-or-entity-name-from-workflow
          (select-keys workflow [:outputs]) {:identifier "a"}))
        "When no inputs, should only check outputs")
    (is (= "aInput"
           (#'sink/throw-or-entity-name-from-workflow workflow {:identifier "a"}))
        "Key a only present in inputs map")
    (is (= "bOutput"
           (#'sink/throw-or-entity-name-from-workflow workflow {:identifier "b"}))
        "Key b present in both inputs and outputs maps should pull from outputs map")
    (is (= "cOutput"
           (#'sink/throw-or-entity-name-from-workflow workflow {:identifier "c"}))
        "Key c only present in outputs map")
    (is (thrown-with-msg?
         ExceptionInfo msg
         (#'sink/throw-or-entity-name-from-workflow workflow {:identifier "d"}))
        "Key d not found in inputs or outputs maps should throw")))

(ns wfl.integration.rawls-test
  (:require [clojure.test          :refer [deftest is testing use-fixtures]]
            [wfl.service.firecloud :as firecloud]
            [wfl.service.rawls     :as rawls]
            [wfl.tools.datasets    :as datasets]
            [wfl.tools.fixtures    :as fixtures]
            [wfl.tools.resources :as resources]
            [wfl.util              :as util])
  (:import [clojure.lang ExceptionInfo]))

;; A known Terra Data Repository snapshot's ID...
(def snapshot-name "snapshot")
(def snapshot-id   "7cb392d8-949b-419d-b40b-d039617d2fc7")

(let [new-env {"WFL_FIRECLOUD_URL"
               "https://firecloud-orchestration.dsde-dev.broadinstitute.org"}]
  (use-fixtures :once (fixtures/temporary-environment new-env)))

(deftest test-snapshot-references
  (fixtures/with-temporary-workspace
    "general-dev-billing-account/test-workspace"
    "hornet-eng"
    (fn [workspace]
      (let [make-reference
            #(rawls/create-snapshot-reference workspace snapshot-id snapshot-name)
            reference-id
            (testing "Create"
              (let [snapshot (make-reference)]
                (is (= "DATA_REPO_SNAPSHOT" (:referenceType snapshot)))
                (is (= snapshot-id (get-in snapshot [:reference :snapshot])))
                (is (= snapshot-name (:name snapshot)))
                (:referenceId snapshot)))]
        (testing "Get"
          (let [snapshot (rawls/get-snapshot workspace reference-id)]
            (is (= "DATA_REPO_SNAPSHOT" (:referenceType snapshot)))
            (is (= snapshot-id (get-in snapshot [:reference :snapshot])))
            (is (= snapshot-name (:name snapshot)))))
        (testing "Create already exists"
          (is (thrown-with-msg? ExceptionInfo #"clj-http: status 409"
                                (make-reference))))))))

(comment
  "outputs.edn generated via"
  (let [workspace  "cdc-covid-surveillance/CDC_Viral_Sequencing_GP"
        pipeline   "sarscov2_illumina_full"
        submission "475d0a1d-20c0-42a1-968a-7540b79fcf0c"
        workflow   "2768b29e-c808-4bd6-a46b-6c94fd2a67aa"])
  (-> (fircloud/get-workflow-outputs workspace submission workflow)
      (get-in [:tasks (keyword pipeline) :outputs])
      (util/unprefix-keys (keyword (str pipeline ".")))))

(deftest test-batch-insert-entities
  (let [entity-type   "flowcell"
        entity-name   "test"
        from-outputs  (resources/read-resource "sarscov2_illumina_full/entity-from-outputs.edn")
        outputs       (resources/read-resource "sarscov2_illumina_full/outputs.edn")]
    (fixtures/with-temporary-workspace
      "general-dev-billing-account/test-workspace"
      "hornet-eng"
      (fn [workspace]
        (let [entity (datasets/rename-gather outputs from-outputs)
              _      (rawls/batch-upsert workspace [[entity-name entity-type entity]])
              [{:keys [name attributes]} & _]
              (util/poll
               #(not-empty (firecloud/list-entities workspace entity-type)))]
          (is (= name entity-name) "The test entity was not created")
          (is (= (util/map-vals #(if (map? %) (:items %) %) attributes)
                 (into {} (filter second entity)))))))))

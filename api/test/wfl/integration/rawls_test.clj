(ns wfl.integration.rawls-test
  (:require [clojure.test          :refer [deftest is testing use-fixtures]]
            [wfl.service.firecloud :as firecloud]
            [wfl.service.rawls     :as rawls]
            [wfl.tools.fixtures    :as fixtures]
            [wfl.tools.resources   :as resources]
            [wfl.util              :as util])
  (:import [clojure.lang ExceptionInfo]))

;; A known TDR Dev snapshot ID
(def snapshot-id "7cb392d8-949b-419d-b40b-d039617d2fc7")

(let [new-env {"WFL_FIRECLOUD_URL"
               "https://firecloud-orchestration.dsde-dev.broadinstitute.org"}]
  (use-fixtures :once (fixtures/temporary-environment new-env)))

(deftest test-snapshot-references
  (fixtures/with-temporary-workspace
    "general-dev-billing-account/test-workspace"
    "hornet-eng"
    (fn [workspace]
      (letfn [(make-reference [snapshot-name]
                (rawls/create-snapshot-reference workspace snapshot-id snapshot-name))
              (verify-reference [[{:keys [attributes metadata] :as _reference}
                                  snapshot-names]]
                (is (= "DATA_REPO_SNAPSHOT" (:resourceType metadata)))
                (is (= snapshot-id (:snapshot attributes)))
                (is (= snapshot-names (:name metadata))))
              (verify-references [references snapshot-names]
                (->> (map list references snapshot-names)
                     (run! verify-reference)))]
        (let [names ["snapshot1" "snapshot2"]
              reference-ids
              (testing "Create"
                (let [references (map make-reference names)]
                  (verify-references references names)
                  (map #(get-in % [:metadata :resourceId]) references)))]
          (testing "Get"
            (let [references
                  (map #(rawls/get-snapshot-reference workspace %) reference-ids)]
              (verify-references references names)))
          (testing "Get all for snapshot id"
            (let [[_first _second & rest :as references]
                  (rawls/get-snapshot-references-for-snapshot-id workspace
                                                                 snapshot-id
                                                                 10)]
              (is (empty? rest))
              (verify-references references names)))
          (testing "Create or get"
            (let [reference (rawls/create-or-get-snapshot-reference workspace
                                                                    snapshot-id
                                                                    (first names))]
              (verify-reference [reference (first names)])))
          (testing "Create already exists"
            (is (thrown-with-msg? ExceptionInfo #"clj-http: status 409"
                                  (make-reference (first names))))))))))

(deftest test-batch-upsert-entities
  (testing "a 1MB entities file can be uploaded"
    (let [entity-type "outputs"
          entity-name "test"
          outputs     (resources/read-resource "sarscov2_illumina_full/outputs.edn")]
      (fixtures/with-temporary-workspace
        "general-dev-billing-account/test-workspace"
        "hornet-eng"
        (fn [workspace]
          (rawls/batch-upsert workspace [[entity-type entity-name outputs]])
          (let [[{:keys [name attributes]} & _]
                (util/poll
                 #(not-empty (firecloud/list-entities workspace entity-type)))]
            (is (= name entity-name) "The test entity was not created")
            (is (= (util/map-vals #(if (map? %) (:items %) %) attributes)
                   (into {} (keep second outputs))))))))))

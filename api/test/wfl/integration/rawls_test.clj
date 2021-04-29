(ns wfl.integration.rawls-test
  (:require [clojure.test       :refer [deftest is testing use-fixtures]]
            [wfl.service.rawls  :as rawls]
            [wfl.tools.fixtures :as fixtures])
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
          (let [snapshot (rawls/get-snapshot-reference workspace reference-id)]
            (is (= "DATA_REPO_SNAPSHOT" (:referenceType snapshot)))
            (is (= snapshot-id (get-in snapshot [:reference :snapshot])))
            (is (= snapshot-name (:name snapshot)))))
        (testing "Create already exists"
          (is (thrown-with-msg? ExceptionInfo #"clj-http: status 409"
                                (make-reference))))))))

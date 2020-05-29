(ns zero.test.api-handlers-test
  (:require [clojure.test :refer [deftest testing is use-fixtures]]
            [zero.api.handlers :refer [add-workload! start-workload!]]
            [zero.test.tools.fixtures :refer [method-overload-fixture]]))

(def dummy-pipeline "unittest")
(defn dummy-handler [_ x] x)

(use-fixtures :once
  (method-overload-fixture add-workload! dummy-pipeline dummy-handler)
  (method-overload-fixture start-workload! dummy-pipeline dummy-handler))

(deftest test-add-workload!
  (testing "add-workload! pipeline entry points are correctly called"
    (testing "dummy pipeline"
      (let [workload {:pipeline dummy-pipeline}]
        (is (= workload (add-workload! nil workload)))))
    (testing "illegal pipeline"
      (let [workload {:pipeline "geoff"}]
        (is (= 400 (:status (add-workload! nil workload))))))))

(deftest test-start-workload!
  (testing "start-workload! pipeline entry points are correctly called"
    (testing "dummy pipeline"
      (let [workload {:pipeline dummy-pipeline}]
        (is (= workload (start-workload! nil workload)))))
    (testing "illegal pipeline"
      (let [workload {:pipeline "geoff"}]
        (is (= 400 (:status (start-workload! nil workload))))))))

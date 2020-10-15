(ns wfl.unit.api-handlers-test
  (:require [clojure.test :refer [deftest testing is use-fixtures]]
            [wfl.api.workloads :refer [create-workload! start-workload! execute-workload!]]
            [wfl.tools.fixtures :refer [method-overload-fixture]]))

(def dummy-pipeline "unittest")
(defn dummy-handler [_ x] x)

(use-fixtures :once
  (method-overload-fixture create-workload! dummy-pipeline dummy-handler)
  (method-overload-fixture start-workload! dummy-pipeline dummy-handler))

(deftest test-create-workload!
  (testing "create-workload! pipeline entry points are correctly called"
    (testing "dummy pipeline"
      (let [workload {:pipeline dummy-pipeline}]
        (is (= workload (create-workload! nil workload)))))
    (testing "illegal pipeline"
      (let [workload {:pipeline "geoff"}]
        (is (thrown? Exception (create-workload! nil workload)))))))

(deftest test-start-workload!
  (testing "start-workload! pipeline entry points are correctly called"
    (testing "dummy pipeline"
      (let [workload {:pipeline dummy-pipeline}]
        (is (= workload (start-workload! nil workload)))))
    (testing "illegal pipeline"
      (let [workload {:pipeline "geoff"}]
        (is (thrown? Exception (start-workload! nil workload)))))))

(deftest test-exec-workload!
  (testing "execute-workload! pipeline entry points are correctly called"
    (testing "dummy pipeline"
      (let [workload {:pipeline dummy-pipeline}]
        (is (= workload (execute-workload! nil workload)))))
    (testing "illegal pipeline"
      (let [workload {:pipeline "geoff"}]
        (is (thrown? Exception (execute-workload! nil workload)))))))

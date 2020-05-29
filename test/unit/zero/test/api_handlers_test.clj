(ns zero.test.api-handlers-test
  (:require [clojure.test :refer [deftest testing is use-fixtures]]
            [zero.api.handlers :refer [add-workload! start-workload!]]))

(def dummy-pipeline "unittest")

(defn use-dummy-method-overloads [run-test]
  (let [fs [add-workload! start-workload!]]
    (doseq [f fs] (defmethod f dummy-pipeline [_ x] x))
    (run-test)
    (doseq [f fs] (remove-method f dummy-pipeline))))

(use-fixtures :once use-dummy-method-overloads)

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

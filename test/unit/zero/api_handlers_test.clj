(ns zero.api-handlers-test
  (:require [clojure.test :refer [deftest testing is]]
            [zero.api.handlers :refer [add-workload! start-workload!]]))

(deftest test-add-workload!
  (testing "add-workload! pipeline entry points are correctly called"
    (testing "`testing` pipeline"
      (let [workload {:pipeline "testing"}]
        (is (= workload (add-workload! nil workload)))))
    (testing "illegal pipeline"
      (let [workload {:pipeline "geoff"}]
        (is (= 400 (:status (add-workload! nil workload))))))))

(deftest test-start-workload!
  (testing "start-workload! pipeline entry points are correctly called"
    (testing "`testing` pipeline"
      (let [workload {:pipeline "testing"}]
        (is (= workload (start-workload! nil workload)))))
    (testing "illegal pipeline"
      (let [workload {:pipeline "geoff"}]
        (is (= 400 (:status (start-workload! nil workload))))))))
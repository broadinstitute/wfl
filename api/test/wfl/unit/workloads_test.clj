(ns wfl.unit.workloads-test
  (:require [clojure.test       :refer [deftest is testing use-fixtures]]
            [wfl.api.workloads  :as workloads]
            [wfl.tools.fixtures :refer [method-overload-fixture]])
  (:import [clojure.lang ExceptionInfo]))

(deftest test-saved-before?
  (testing "in version 0.4.0, wgs workloads were serialized using CromwellWorkload table"
    (is (not (workloads/saved-before? "0.4.0" {:version "0.4.0"})))
    (is (not (workloads/saved-before? "0.4.0" {:version "1.0.0"})))
    (is (not (workloads/saved-before? "0.4.0" {:version "0.4.1"})))
    (is (workloads/saved-before? "0.4.0" {:version "0.3.8"}))
    (is (workloads/saved-before? "0.4.0" {:version "0.0.0"}))
    (is (thrown? ExceptionInfo (workloads/saved-before? "0.4" {:version "0.0.0"})))))

(def dummy-pipeline "unittest")
(defn dummy-handler [_ x] x)

(use-fixtures :once
  (method-overload-fixture workloads/create-workload! dummy-pipeline dummy-handler)
  (method-overload-fixture workloads/start-workload! dummy-pipeline dummy-handler))

(deftest test-create-workload!
  (testing "create-workload! pipeline entry points are correctly called"
    (testing "dummy pipeline"
      (let [workload {:pipeline dummy-pipeline}]
        (is (= workload (workloads/create-workload! nil workload)))))
    (testing "illegal pipeline"
      (let [workload {:pipeline "geoff"}]
        (is (thrown-with-msg? Exception #"Failed to create workload - no such pipeline"
                              (workloads/create-workload! nil workload)))))))

(deftest test-start-workload!
  (testing "start-workload! pipeline entry points are correctly called"
    (testing "dummy pipeline"
      (let [workload {:pipeline dummy-pipeline}]
        (is (= workload (workloads/start-workload! nil workload)))))
    (testing "illegal pipeline"
      (let [workload {:pipeline "geoff"}]
        (is (thrown-with-msg? Exception #"Failed to start workload - no such pipeline"
                              (workloads/start-workload! nil workload)))))))

(deftest test-exec-workload!
  (testing "execute-workload! pipeline entry points are correctly called"
    (testing "dummy pipeline"
      (let [workload {:pipeline dummy-pipeline}]
        (is (= workload (workloads/execute-workload! nil workload)))))
    (testing "illegal pipeline"
      (let [workload {:pipeline "geoff"}]
        (is (thrown-with-msg? Exception #"Failed to create workload - no such pipeline"
                              (workloads/execute-workload! nil workload)))))))

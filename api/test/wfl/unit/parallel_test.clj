(ns wfl.unit.parallel-test
  (:require [clojure.test :refer [deftest testing is]]
            [wfl.tools.workloads :as workloads]
            [wfl.api.spec :as spec]
            [clojure.spec.alpha :as s])
  (:import (java.util UUID)))

(deftest request-spec-test
  (testing "Requests are valid"
    (letfn [(valid? [req] (is (s/valid? ::spec/workload-request req)))]
      (run! valid? (map #(% (UUID/randomUUID))
                        [workloads/wgs-workload-request
                         workloads/aou-workload-request
                         workloads/xx-workload-request])))))

(deftest ^:parallel long-test
  (testing "Requests are valid"
    (letfn [(valid? [req] (is (s/valid? ::spec/workload-request req)))]
      (Thread/sleep 10000)
      (run! valid? (map #(% (UUID/randomUUID))
                        [workloads/wgs-workload-request
                         workloads/aou-workload-request
                         workloads/xx-workload-request])))))

(deftest ^:parallel another-long-test
  (testing "Requests are valid"
    (letfn [(valid? [req] (is (s/valid? ::spec/workload-request req)))]
      (Thread/sleep 10000)
      (run! valid? (map #(% (UUID/randomUUID))
                        [workloads/wgs-workload-request
                         workloads/aou-workload-request
                         workloads/xx-workload-request])))))

(ns wfl.unit.spec-test
  (:require [clojure.test :refer [deftest testing is]]
            [wfl.tools.workloads :as workloads]
            [wfl.api.spec :as spec]
            [clojure.spec.alpha :as s]
            [clojure.walk :as walk])
  (:import (java.util UUID)))

(deftest request-spec-test
  (testing "Requests are valid"
    (letfn [(valid? [req] (is (s/valid? ::spec/workload-request req)))]
      (run! valid? (map #(% (UUID/randomUUID))
                        [workloads/wgs-workload-request
                         workloads/aou-workload-request
                         workloads/xx-workload-request]))))
  (testing "Mismatched cram/bam inputs cannot pass spec validation"
    (letfn [(valid? [req] (is (not (s/valid? ::spec/workload-request req))))]
      (let [requests (map #(% (UUID/randomUUID))
                          [workloads/wgs-workload-request
                           workloads/xx-workload-request])
            mismatched-requests (map #(walk/postwalk-replace {:input_cram :input_bam} %)
                                     requests)]
        (run! valid? mismatched-requests)))))

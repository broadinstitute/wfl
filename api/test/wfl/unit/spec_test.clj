(ns wfl.unit.spec-test
  (:require [clojure.spec.alpha  :as s]
            [clojure.test        :refer [deftest testing is]]
            [wfl.api.spec        :as spec]
            [wfl.tools.workloads :as workloads])
  (:import [java.util UUID]))

(deftest request-spec-test
  (testing "Requests are valid"
    (letfn [(valid? [req] (is (s/valid? ::spec/workload-request req)))]
      (run! valid? (map #(% (UUID/randomUUID))
                        [workloads/wgs-workload-request
                         workloads/aou-workload-request
                         workloads/xx-workload-request])))))

(deftest workload-query-spec-test
  (letfn [(invalid? [req] (is (not (s/valid? ::spec/workload-query req))))
          (valid? [req] (is (s/valid? ::spec/workload-query req)))]
    (let [uuid (str (UUID/randomUUID))
          project "bogus-project"]
      (testing "Workload UUID and project cannot be specified together"
        (let [request {:uuid uuid :project project}]
          (run! invalid? request)))
      (testing "Workload UUID and project can be omitted together"
        (let [request {}]
          (run! valid? request)))
      (testing "Either workload UUID or project can be specified"
        (let [uuid-request {:uuid uuid}
              proj-request {:project project}]
          (run! valid? [uuid-request proj-request]))))))

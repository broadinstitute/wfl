(ns wfl.integration.modules.aou-test
  (:require [clojure.spec.alpha :as s]
            [clojure.test :refer [testing is deftest use-fixtures]]
            [wfl.api.spec]
            [wfl.module.aou :as aou]
            [wfl.service.cromwell :refer [submit-workflow]]
            [wfl.service.postgres :as postgres]
            [wfl.tools.fixtures :as fixtures]
            [wfl.tools.workloads :as workloads]
            [wfl.tools.endpoints :as endpoints])
  (:import (java.util UUID)))

(use-fixtures :once fixtures/temporary-postgresql-database)

(defn mock-submit-workload [& _] (UUID/randomUUID))
(def mock-cromwell-status (constantly "Succeeded"))

(defn ^:private make-aou-workload-request []
  (-> (workloads/aou-workload-request (UUID/randomUUID))
    (assoc :creator (:email @endpoints/userinfo))))

(defn ^:private inc-version [sample]
  (update sample :analysis_version_number inc))

(def count=1? (comp (partial == 1) count))

(deftest test-append-to-aou
  (with-redefs-fn {#'aou/submit-aou-workflow mock-submit-workload}
    #(let [workload            (workloads/execute-workload! (make-aou-workload-request))
           append-to-workload! (fn [xs] (workloads/append-to-workload! xs workload))]
       (testing "appending a sample to the workload"
         (let [response (append-to-workload! [workloads/aou-sample])]
           (is (s/valid? :wfl.api.spec/append-to-aou-response response))
           (is (count=1? response))))
       (testing "appending the same sample to the workload does nothing"
         (is (empty? (append-to-workload! [workloads/aou-sample]))))
       (testing "incrementing analysis_version_number starts a new workload"
         (is (count=1? (append-to-workload! [(inc-version workloads/aou-sample)]))))
       (testing "only one workflow is started when there are multiple duplicates"
         (is (count=1?
               (append-to-workload!
                 (repeat 5 (inc-version (inc-version workloads/aou-sample)))))))
       (testing "appending empty workload"
         (let [response (append-to-workload! [])]
           (is (s/valid? :wfl.api.spec/append-to-aou-response response))
           (is (empty? response)))))))

(deftest test-append-to-aou-not-started
  (with-redefs-fn {#'aou/submit-aou-workflow mock-submit-workload}
    #(is
       (thrown? Exception
         (workloads/append-to-workload! [workloads/aou-sample]
           (workloads/create-workload! (make-aou-workload-request)))))))

(deftest test-aou-cannot-be-stopped!
  (with-redefs-fn {#'aou/submit-aou-workflow  mock-submit-workload
                   #'postgres/cromwell-status mock-cromwell-status}
    #(let [workload (-> (make-aou-workload-request)
                      (workloads/execute-workload!)
                      (workloads/update-workload!))]
       (is (not (:finished workload)))
       (workloads/append-to-workload! [workloads/aou-sample] workload)
       (let [workload (workloads/load-workload-for-uuid (:uuid workload))]
         (is (every? (comp #{"Submitted"} :status) (:workflows workload)))
         (let [workload (workloads/update-workload! workload)]
           (is (every? (comp #{"Succeeded"} :status) (:workflows workload)))
           (is (not (:finished workload))))))))

(deftest test-exec-on-same-workload-request
  "executing a workload-request twice should not create a new workload"
  (let [request (make-aou-workload-request)]
    (is (= (workloads/execute-workload! request)
          (workloads/execute-workload! request)))))

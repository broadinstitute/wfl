(ns wfl.integration.modules.aou-test
  (:require [clojure.test :refer [testing is deftest use-fixtures]]
            [wfl.jdbc :as jdbc]
            [wfl.module.aou :as aou]
            [wfl.service.postgres :as postgres]
            [wfl.tools.fixtures :as fixtures]
            [wfl.tools.workloads :as workloads]
            [wfl.tools.endpoints :as endpoints])
  (:import (java.util UUID)))

(use-fixtures :once fixtures/clean-db-fixture)

(defn mock-submit-workload [& _] (UUID/randomUUID))

(defn- make-aou-workload-request []
  (-> (workloads/aou-workload-request (UUID/randomUUID))
    (assoc :creator (:email @endpoints/userinfo))))

(defn- append-to-workload! [samples workload]
  (jdbc/with-db-transaction [tx (postgres/wfl-db-config)]
    (aou/append-to-workload! tx samples workload)))

(defn- inc-version [sample]
  (update sample :analysis_version_number inc))

(def count=1? (comp (partial == 1) count))

(deftest test-append-to-aou
  (with-redefs-fn {#'aou/submit-aou-workflow mock-submit-workload}
    #(let [workload (workloads/execute-workload! (make-aou-workload-request))]
       (testing "appending a sample to the workload"
         (is (count=1? (append-to-workload! [workloads/aou-sample] workload))))
       (testing "appending the same sample to the workload does nothing"
         (is (empty? (append-to-workload! [workloads/aou-sample] workload))))
       (testing "incrementing analysis_version_number starts a new workload"
         (is (count=1?
               (append-to-workload!
                 [(inc-version workloads/aou-sample)]
                 workload))))
       (testing "only one workflow is started when there are multiple duplicates"
         (is (count=1?
               (append-to-workload!
                 (repeat 5 (inc-version (inc-version workloads/aou-sample)))
                 workload))))
       (testing "appending empty workload"
         (is (empty? (append-to-workload! [] workload)))))))

(deftest test-append-to-aou-not-started
  (with-redefs-fn {#'aou/submit-aou-workflow mock-submit-workload}
    #(is
       (thrown? Exception
         (append-to-workload! [workloads/aou-sample]
           (workloads/create-workload! (make-aou-workload-request)))))))

(ns wfl.integration.workloads-test
  (:require [clojure.test :refer [deftest testing is] :as clj-test]
            [clojure.test :refer :all]
            [wfl.jdbc :as jdbc]
            [wfl.module.all :as all]
            [wfl.module.copyfile :as copyfile]
            [wfl.tools.endpoints :as endpoints]
            [wfl.tools.fixtures :as fixtures]
            [wfl.tools.workloads :as workloads]))

(clj-test/use-fixtures :once fixtures/temporary-postgresql-database)

(defn ^:private make-copyfile-workload-request-with-project
  [src dst project]
  (-> (workloads/copyfile-workload-request src dst)
      (assoc :creator (:email @endpoints/userinfo) :project project)))

(deftest test-loading-copyfile-workloads-with-project
  (let [upper-project "TEST-PROJECT"
        lower-project "test-project"
        bogus-project "bogus"]
    (letfn [(create-copyfile-workload! [project]
              (-> (make-copyfile-workload-request-with-project "gs://fake/input" "gs://fake/output" project)
                  workloads/create-workload!))
            (create-a-bunch-copyfile-workloads! []
              (dotimes [n 2] (create-copyfile-workload! upper-project))
              (dotimes [n 2] (create-copyfile-workload! lower-project)))
            (verify-copyfile-workloads-identity [project workloads]
              (is (every? #(= copyfile/pipeline (:pipeline %)) workloads))
              (is (every? #(= project (:project %)) workloads)))]
      (create-a-bunch-copyfile-workloads!)
      (testing "No matching returns empty list"
        (is empty? (workloads/load-workloads-with-project bogus-project)))
      (testing "Query project parameter is case-sensitive"
        (let [fetched-workloads (workloads/load-workloads-with-project upper-project)]
          (verify-copyfile-workloads-identity upper-project fetched-workloads))))))

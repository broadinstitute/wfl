(ns wfl.integration.modules.covid-test
  (:require [clojure.test :refer :all]
            [clojure.test :as clj-test]
            [wfl.tools.fixtures :as fixtures]
            [wfl.tools.workloads :as workloads]))

(clj-test/use-fixtures :once fixtures/temporary-postgresql-database)

(def ^:private testing-dataset "ff6e2b40-6497-4340-8947-2f52a658f561")
(def ^:private testing-workspace "wfl-dev/CDC_Viral_Sequencing")
(def ^:private testing-method-configuration "pathogen-genomic-surveillance/sarscov2_illumina_full")

(defn ^:private make-covid-workload-request []
  (-> (workloads/covid-workload-request testing-dataset
                                        testing-method-configuration
                                        testing-workspace)
      (assoc :creator @workloads/email)))

(deftest test-create-covid-workload
  (testing "That COVID workload-request can pass verification"
    (let [workload (workloads/create-workload! (make-covid-workload-request))]
      ;; NOTE: COVID workload creation returns nil until it's fully implemented
      (is workload)))
  (testing "That a COVID workload-request with a misnamed source cannot pass verification"
    (is (thrown? RuntimeException (-> (make-covid-workload-request)
                                      (assoc-in [:source :name] "Bad_Name")
                                      workloads/create-workload!))))
  (testing "That a COVID workload-request without a named source cannot pass verification"
    (is (thrown? RuntimeException (-> (make-covid-workload-request)
                                      (assoc-in [:source :name] nil)
                                      workloads/create-workload!))))
  (testing "That a COVID workload-request without a dataset cannot pass verification"
    (is (thrown? RuntimeException (-> (make-covid-workload-request)
                                      (assoc-in [:source :dataset] nil)
                                      workloads/create-workload!))))

  (testing "That a COVID workload-request with a misnamed executor cannot pass verification"
    (is (thrown? RuntimeException (-> (make-covid-workload-request)
                                      (assoc-in [:executor :name] "Bad_Name")
                                      workloads/create-workload!))))
  (testing "That a COVID workload-request without a named executor cannot pass verification"
    (is (thrown? RuntimeException (-> (make-covid-workload-request)
                                      (assoc-in [:executor :name] nil)
                                      workloads/create-workload!))))
  (testing "That a COVID workload-request without a method_configuration cannot pass verification"
    (is (thrown? RuntimeException (-> (make-covid-workload-request)
                                      (assoc-in [:executor :method_configuration] nil)
                                      workloads/create-workload!))))

  (testing "That a COVID workload-request with a misnamed sink cannot pass verification"
    (is (thrown? RuntimeException (-> (make-covid-workload-request)
                                      (assoc-in [:sink :name] "Bad_Name")
                                      workloads/create-workload!))))
  (testing "That a COVID workload-request without a named sink cannot pass verification"
    (is (thrown? RuntimeException (-> (make-covid-workload-request)
                                      (assoc-in [:sink :name] nil)
                                      workloads/create-workload!))))
  (testing "That a COVID workload-request without a workspace cannot pass verification"
    (is (thrown? RuntimeException (-> (make-covid-workload-request)
                                      (assoc-in [:sink :workspace] nil)
                                      workloads/create-workload!))))
  )

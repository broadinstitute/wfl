(ns wfl.integration.modules.covid-test
  (:require [clojure.test :refer :all]
            [clojure.test :as clj-test]
            [wfl.tools.fixtures :as fixtures]
            [wfl.tools.workloads :as workloads]))

(clj-test/use-fixtures :once fixtures/temporary-postgresql-database)

(def ^:private testing-dataset "ff6e2b40-6497-4340-8947-2f52a658f561")
(def ^:private testing-workspace "general-dev-billing-account/test-snapshots")
(def ^:private testing-method-configuration "pathogen-genomic-surveillance/sarscov2_illumina_full")

(defn ^:private make-covid-workload-request []
  (-> (workloads/covid-workload-request testing-dataset testing-method-configuration testing-workspace)
      (assoc :creator @workloads/email)))

(deftest test-create-covid-workload
  (testing "COVID workload-request can pass verification"
    (let [workload (workloads/create-workload! (make-covid-workload-request))]
      ;; NOTE: COVID workload creation returns nil until it's fully implemented
      (is (nil? workload))))
  (testing "malformed COVID workload-request cannot pass verification"
    (is (thrown? RuntimeException (-> (make-covid-workload-request)
                                      (assoc-in [:executor :method_configuration] "bogus-method-configuration")
                                      workloads/create-workload!)))))

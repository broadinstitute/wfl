(ns wfl.unit.workloads-test
  (:require [clojure.test :refer :all]
            [wfl.api.workloads :as workloads]))

(deftest test-saved-before?
  "in version 0.4.0, wgs workloads were serialized using CromwellWorkload table"
  (is (not (workloads/saved-before? "0.4.0" {:version "0.4.0"})))
  (is (not (workloads/saved-before? "0.4.0" {:version "1.0.0"})))
  (is (not (workloads/saved-before? "0.4.0" {:version "0.4.1"})))
  (is (workloads/saved-before? "0.4.0" {:version "0.3.8"}))
  (is (workloads/saved-before? "0.4.0" {:version "0.0.0"})))

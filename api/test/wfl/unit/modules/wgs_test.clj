(ns wfl.unit.modules.wgs-test
  (:require [clojure.test :refer :all]
            [wfl.module.wgs :as wgs]))

(deftest test-serialized-version
  "in version 0.4.0, wgs workloads were serialized using CromwellWorkload table"
  (is (wgs/uses-cromwell-workload-table? {:version "0.4.0"}))
  (is (wgs/uses-cromwell-workload-table? {:version "1.0.0"}))
  (is (not (wgs/uses-cromwell-workload-table? {:version "0.3.8"}))))

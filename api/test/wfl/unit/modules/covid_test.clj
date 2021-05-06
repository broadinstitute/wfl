(ns wfl.unit.modules.covid-test
  (:require [clojure.test       :refer :all]
            [wfl.module.covid   :as covid]
            [wfl.tools.fixtures :as fixtures]))

(use-fixtures :once fixtures/temporary-postgresql-database)

(deftest test-start-workload
  (testing "wfl.module.covid/start-covid-workload!"))

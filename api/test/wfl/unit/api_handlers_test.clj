(ns wfl.unit.api-handlers-test
  (:require [clojure.test       :refer [deftest is use-fixtures]]
            [wfl.api.handlers   :as handlers]
            [wfl.api.workloads  :as workloads]
            [wfl.tools.fixtures :as fixtures]))

(use-fixtures :once
  (fixtures/method-overload-fixture workloads/workflows "Test" :workflows))

(deftest test-strip-internals
  (let [workload  (handlers/strip-internals
                   {:id 0
                    :pipeline "Test"
                    :items "ExampleTable_0000001"
                    :workflows [{:id 0} {:id 1}]})]
    (is (wfl.util/absent? workload :id))
    (is (wfl.util/absent? workload :items))
    (is (every? #(wfl.util/absent? % :id) (:workflows workload)))))

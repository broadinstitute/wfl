(ns wfl.unit.api-handlers-test
  (:require [clojure.test       :refer [deftest is use-fixtures]]
            [wfl.api.handlers   :as handlers]))

(deftest test-strip-internals
  (let [workload  (#'handlers/strip-internals
                   {:id 0
                    :pipeline "Test"
                    :items "ExampleTable_0000001"})]
    (is (wfl.util/absent? workload :id))
    (is (wfl.util/absent? workload :items))
    (is (wfl.util/absent? workload :workflows))))

(ns wfl.unit.api-handlers-test
  (:require [clojure.test :refer [deftest is]]
            [wfl.api.handlers :as handlers]))

(deftest test-strip-internals
  (let [workload  (handlers/strip-internals
                   {:id 0
                    :items "ExampleTable_0000001"
                    :workflows [{:id 0} {:id 1}]})]
    (is (wfl.util/absent? workload :id))
    (is (wfl.util/absent? workload :items))
    (is (every? #(wfl.util/absent? % :id) (:workflows workload)))))

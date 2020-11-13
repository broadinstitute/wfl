(ns wfl.unit.api-handlers-test
  (:require [clojure.test :refer [deftest is]]
            [wfl.api.handlers :as handlers]))


(deftest test-strip-internals
  (let [workload  (handlers/strip-internals
                    {:id 0 :workflows [{:id 0} {:id 1}]})]
    (is (wfl.util/absent? workload :id))
    (is (every? #(wfl.util/absent? % :id) (:workflows workload)))))

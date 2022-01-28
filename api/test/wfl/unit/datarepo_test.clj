(ns wfl.unit.datarepo-test
  (:require [clojure.test         :refer [deftest is]]
            [wfl.service.datarepo :as datarepo]))

(deftest test-where-between
  (is (= "column BETWEEN 'start' AND 'end'"
         (datarepo/where-between :column ["start" "end"])))
  (is (= "column BETWEEN 'start' AND 'end'"
         (datarepo/where-between "column" ["start" "end"]))))

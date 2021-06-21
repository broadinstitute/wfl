(ns wfl.unit.workflows-test
  (:require [clojure.test        :refer [deftest is testing]]
            [wfl.tools.workflows :as workflows]
            [wfl.tools.resources :as resources]
            [wfl.util            :as util])
  (:import (clojure.lang ExceptionInfo)))

(defn ^:private lift-value [f] (fn [_ value] (f value)))
(def ^:private  vectorize (partial workflows/traverse (lift-value vector)))

(defn ^:private make-output-type [resource-file]
  (-> resource-file
      resources/read-resource
      :outputs
      workflows/make-object-type))

(deftest test-array-types
  (let [type    (make-output-type "compound.edn")
        outputs {:outarray  ["clojure" "is" "fun"]}]
    (testing "foldl"
      (is (= (:outarray outputs)
             (workflows/foldl #(conj %1 %3) [] type outputs))))
    (testing "traverse"
      (is (= (util/map-vals #(map vector %) outputs)
             (vectorize type outputs))))))



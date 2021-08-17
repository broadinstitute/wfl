(ns wfl.unit.workflows-test
  (:require [clojure.test        :refer [deftest is testing]]
            [wfl.tools.resources :as resources]
            [wfl.tools.workflows :as workflows]
            [wfl.util            :as util])
  (:import (clojure.lang ExceptionInfo)))

(defn ^:private collect-values-where-type [test? object]
  (letfn [(f [vs [t v]] (if (test? t) (conj vs v) vs))]
    (workflows/foldl f #{} object)))

(def ^:private collect-primitives
  (letfn [(f [ts [t _]] (conj ts t))]
    (partial workflows/foldl f #{})))

(defn ^:private lift-value [f] (comp f second))
(def ^:private  vectorize (partial workflows/traverse (lift-value vector)))

(defn ^:private make-output-type [resource-file]
  (-> resource-file
      resources/read-resource
      :outputs
      workflows/make-object-type))

(deftest test-no-such-name
  (let [type {:typeName "Object" :objectFieldTypes []}]
    (testing "foldl"
      (is (thrown-with-msg?
           ExceptionInfo
           #"No type definition found for name\."
           (workflows/foldl (constantly nil) nil [type {:no-such-name nil}]))))
    (testing "traverse"
      (is (thrown-with-msg?
           ExceptionInfo
           #"No type definition found for name\."
           (workflows/traverse (constantly nil) [type {:no-such-name nil}]))))))

(deftest test-primitive-types
  (let [type (make-output-type "primitive.edn")
        outputs {:outbool   true
                 :outfile   "lolcats.txt"
                 :outfloat  3.14
                 :outint    3
                 :outstring "Hello, World!"}]
    (testing "foldl"
      (is (= #{"Boolean" "File" "Float" "Int" "String"}
             (collect-primitives [type outputs])))
      (is (= #{"Boolean" "File" "Float" "Int"}
             (collect-primitives [type (dissoc outputs :outstring)])))
      (is (= #{"lolcats.txt"} (workflows/get-files [type outputs]))))
    (testing "traverse"
      (is (= (util/map-vals vector outputs) (vectorize [type outputs]))))))

(deftest test-array-types
  (letfn [(typeless [p1 [_p2 p3]] (conj p1 p3))]
    (let [type    (make-output-type "compound.edn")
          outputs {:outarray  ["clojure" "is" "fun"]}]
      (testing "foldl"
        (is (= (:outarray outputs)
               (workflows/foldl typeless [] [type outputs]))))
      (testing "traverse"
        (is (= (util/map-vals #(map vector %) outputs)
               (vectorize [type outputs])))))))

(deftest test-map-types
  (let [type    (make-output-type "compound.edn")
        outputs {:outmap {"foo" "lolcats.txt" "bar" "in.gif"}}]
    (testing "foldl"
      (is (= #{"foo" "bar"}
             (collect-values-where-type #(= % "String") [type outputs])))
      (is (= #{"lolcats.txt" "in.gif"}
             (collect-values-where-type #(= % "File") [type outputs]))))
    (testing "traverse"
      (is (= {:outmap {["foo"] ["lolcats.txt"] ["bar"] ["in.gif"]}}
             (vectorize [type outputs]))))))

(deftest test-optional-types
  (let [type    (make-output-type "compound.edn")
        without {:outoptional  nil}
        with    {:outoptional  "lolcats.txt"}]
    (testing "foldl"
      (is (= 0 (count (workflows/get-files [type without]))))
      (is (= 1 (count (workflows/get-files [type with])))))
    (testing "traverse"
      (is (= without (vectorize [type without])))
      (is (= (util/map-vals vector with) (vectorize [type with]))))))

(deftest test-pair-types
  (let [object [(make-output-type "compound.edn") {:outpair '(3 3.14)}]]
    (testing "foldl"
      (is (= #{3.14} (collect-values-where-type #(= % "Float") object))))
    (testing "traverse"
      (is (= {:outpair '(9 3.14)}
             (workflows/traverse
              (fn [[type v]] (if (= type "Int") (* v v) v))
              object))))))

(deftest test-struct-types
  (let [object [(make-output-type "compound.edn") {:outstruct {:value 3}}]]
    (testing "foldl"
      (is (= #{3} (collect-values-where-type #(= % "Int") object))))
    (testing "traverse"
      (is (= {:outstruct {:value 9}}
             (workflows/traverse
              (fn [[type v]] (if (= type "Int") (* v v) v))
              object))))))

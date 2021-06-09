(ns wfl.unit.utils-test
  (:require [clojure.test :refer [deftest is testing]]
            [wfl.util :as util]
            [clojure.java.io :as io])
  (:import [java.io IOException]
           [org.apache.commons.io FileUtils]))

(deftest test-extract-resource
  (testing "extracting a valid resource"
    (let [resource (util/extract-resource "wdl/copyfile.wdl")]
      (is (.exists resource) "The resource was not extracted")
      (is (FileUtils/contentEquals (io/file "resources/wdl/copyfile.wdl") resource))))
  (testing "invalid resources should throw"
    (is (thrown? IOException (util/extract-resource "/foo/bar.baz")))))

(defn testing-equality-on [transform]
  (fn [[input expected]]
    (is (= expected (transform input)))))

(deftest test-leafname
  (let [go         (testing-equality-on util/basename)
        parameters [["" ""]
                    ["/" "/"]
                    ["foo" "foo"]
                    ["/path/to/foo" "foo"]
                    ["path/to/foo" "foo"]
                    ["./path/to/foo/" "foo"]]]
    (run! go parameters)))

(deftest test-remove-extension
  (let [go         (testing-equality-on util/remove-extension)
        parameters [["file" "file"]
                    ["file.txt" "file"]
                    ["file.tar.gz" "file.tar"]
                    ["file." "file"]
                    ["" ""]]]
    (run! go parameters)))

(deftest test-dirname
  (let [go         (testing-equality-on util/dirname)
        parameters [["" ""]
                    ["/" "/"]
                    ["foo.txt" ""]
                    ["path/to/foo" "path/to"]
                    ["/path/to/foo/" "/path/to"]]]
    (run! go parameters)))

(deftest test-deep-merge
  (letfn [(go [[first second expected]]
              (is (= expected (util/deep-merge first second))))]
    (let [parameters [[nil nil nil]
                      [nil {} {}]
                      [{} nil {}]
                      [{} {} {}]
                      [{:a 0} {:b 1} {:a 0 :b 1}]
                      [{:a 0} {:a 1} {:a 1}]
                      [{:a {:b {:c 0}}} {:a {:b {:d 1}}} {:a {:b {:c 0 :d 1}}}]]]
      (run! go parameters))))

(deftest test-assoc-when
  (is (= {:a 2} (util/assoc-when {:a 1} contains? :a 2)))
  (is (= {:a 1} (util/assoc-when {:a 1} (constantly false) :a 2))))

(deftest test-terra-id
  (is (thrown? AssertionError (util/terra-id "not-in-tsv-type-spec" "flowcell")))
  (letfn [(go [[first second expected]]
              (is (= expected (util/terra-id first second))))]
    (let [entity "entity:flowcell_id"
          membership "membership:flowcell_set_id"
          parameters [[:entity "flowcell" entity]
                      [:entity "flowcell_id" entity]
                      [:membership "flowcell" membership]
                      [:membership "flowcell_id" membership]
                      [:membership "flowcell_set" membership]
                      [:membership "flowcell_set_id" membership]]]
      (run! go parameters))))

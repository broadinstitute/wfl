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

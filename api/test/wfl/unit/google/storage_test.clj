(ns wfl.unit.google.storage-test
  (:require [clojure.test :refer [deftest is testing]]
            [wfl.service.gcs :as gcs]))

(deftest gs-url-test
  (testing "URL utilities"
    (testing "parse-gs-url ok"
      (is (= ["b" "obj/ect"]  (gcs/parse-gs-url "gs://b/obj/ect")))
      (is (= ["b" "obj/ect/"] (gcs/parse-gs-url "gs://b/obj/ect/")))
      (is (= ["b" ""]         (gcs/parse-gs-url "gs://b/")))
      (is (= ["b" ""]         (gcs/parse-gs-url "gs://b"))))
    (testing "gs-url ok"
      (is (= "gs://b/ob/je/ct"  (gcs/gs-url "b" "ob/je/ct")))
      (is (= "gs://b/ob/je/ct/" (gcs/gs-url "b" "ob/je/ct/")))
      (is (= "gs://b/"          (gcs/gs-url "b" "")))
      (is (= "gs://b/"          (gcs/gs-url "b" nil))))
    (testing "parse-gs-url bad"
      (is (thrown? IllegalArgumentException (gcs/parse-gs-url "")))
      (is (thrown? IllegalArgumentException (gcs/parse-gs-url "x")))
      (is (thrown? IllegalArgumentException (gcs/parse-gs-url "x/y")))
      (is (thrown? IllegalArgumentException (gcs/parse-gs-url "/")))
      (is (thrown? IllegalArgumentException (gcs/parse-gs-url "file://x/y")))
      (is (thrown? IllegalArgumentException (gcs/parse-gs-url "gs:")))
      (is (thrown? IllegalArgumentException (gcs/parse-gs-url "gs:/b/o")))
      (is (thrown? IllegalArgumentException (gcs/parse-gs-url "gs:///o/"))))
    (testing "gs-url bad"
      (is (thrown? IllegalArgumentException (gcs/gs-url ""  "")))
      (is (thrown? IllegalArgumentException (gcs/gs-url ""  "o"))))))

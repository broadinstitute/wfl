(ns wfl.unit.gcs-test
  "Test the Google Cloud Storage namespace."
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [wfl.once :as once]
            [wfl.service.gcs :as gcs]
            [wfl.tools.fixtures :refer [with-temporary-gcs-folder]])
  (:import [java.util UUID]))

(def project
  "Test in this Google Cloud project."
  "broad-gotc-dev-storage")

(def blame
  "Who is to blame?"
  (or (System/getenv "USER") "wfl"))

(def uid
  "A new unique string compressed from a random UUID."
  (str/replace (str (UUID/randomUUID)) "-" ""))

(def prefix
  "A unique prefix for naming things in GCS."
  (str/join "-" [blame "test" uid ""]))

(def buckets
  "Make some unique GCS bucket names for testing."
  (mapv (fn [n] (str prefix n)) (range 2)))

(defn make-bucket
  "Make a bucket named BUCKET."
  [bucket]
  (gcs/make-bucket project bucket "US" "STANDARD"))

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

(deftest bucket-test
  (testing "Buckets"
    (testing "bad names"
      (let [too-short  (str/join (take 2 (str blame blame)))
            too-long   (str/join (take 64 (str prefix uid uid)))
            uppercase  (str prefix "Uppercase")
            underfirst (str "_" prefix 0)]
        (is (thrown? IllegalArgumentException (make-bucket too-short)))
        (is (thrown? IllegalArgumentException (make-bucket too-long)))
        (is (thrown? IllegalArgumentException (make-bucket uppercase)))
        (is (thrown? IllegalArgumentException (make-bucket prefix)))
        (is (thrown? IllegalArgumentException (make-bucket underfirst)))))
    (let [bucket (first buckets)]
      (testing "make"
        (is (= [bucket "US" "STANDARD"]
               ((juxt :name :location :storageClass)
                (make-bucket bucket)))))
      (testing "list project"
        (is (<= 1 (count (gcs/list-buckets project)))))
      (testing "list project prefix"
        (let [result (gcs/list-buckets project prefix)]
          (is (= 1 (count result)))
          (is (= bucket (:name (first result))))))
      (testing "delete"
        (is (gcs/delete-bucket bucket))
        (is (not (gcs/delete-bucket bucket)))))))

(def local-file-name
  "A disposable local file name for object-test."
  (str/join "-" [blame "junk" uid]))

(defn cleanup-object-test
  "Clean up after the object-test."
  []
  (io/delete-file local-file-name :silently))

(deftest object-test
  (try
    (with-temporary-gcs-folder uri
      (testing "Objects"
        (let [[bucket src-folder] (gcs/parse-gs-url uri)
              dest-folder (str src-folder "destination/")
              object {:name (str src-folder "test") :contentType "text/x-java-properties"}
              properties "boot.properties"]
          (testing "upload"
            (let [result (gcs/upload-file properties bucket (:name object))]
              (is (= object (select-keys result (keys object))))
              (is (= bucket (:bucket result)))))
          (testing "list"
            (let [result (gcs/list-objects bucket src-folder)]
              (is (= 1 (count result)))
              (is (= object (select-keys (first result) (keys object))))))
          (testing "copy"
            (is (gcs/copy-object bucket (str src-folder "test") bucket (str dest-folder "test"))))
          (testing "download"
            (gcs/download-file local-file-name bucket (str dest-folder "test"))
            (is (= (slurp properties) (slurp local-file-name)))))))
  (finally (cleanup-object-test))))

(deftest userinfo-test
  (testing "no \"Authorization\" header in request should throw"
    (is (thrown? Exception (gcs/userinfo {:headers {}}))))
  (testing "fetching userinfo from request with \"Authorization\" header"
    (let [info (gcs/userinfo {:headers (once/get-auth-header)})]
      (is (:email info)))))

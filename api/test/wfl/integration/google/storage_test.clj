(ns wfl.integration.google.storage-test
  "Test the Google Cloud Storage namespace."
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [wfl.once :as once]
            [wfl.service.google.storage :as gcs]
            [wfl.tools.fixtures :refer [with-temporary-cloud-storage-folder]]
            [wfl.tools.fixtures :as fixtures])
  (:import [java.util UUID]))

(def project
  "Test in this Google Cloud project."
  "broad-gotc-dev-storage")

(def uid
  "A new unique string compressed from a random UUID."
  (str/replace (str (UUID/randomUUID)) "-" ""))

(def local-file-name
  "A disposable local file name for object-test."
  (str/join "-" ["wfl" "test" uid]))

(defn cleanup-object-test
  "Clean up after the object-test."
  []
  (io/delete-file local-file-name :silently))

(deftest object-test
  (try
    (with-temporary-cloud-storage-folder fixtures/gcs-test-bucket
      (fn [url]
        (testing "Objects"
          (let [[bucket src-folder] (gcs/parse-gs-url url)
                dest-folder (str src-folder "destination/")
                object {:name (str src-folder "test")
                        :contentType "text/plain"}
                dockerfile "Dockerfile"]
            (testing "upload"
              (let [result (gcs/upload-file dockerfile bucket (:name object))]
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
              (is (= (slurp dockerfile) (slurp local-file-name))))))))
    (finally (cleanup-object-test))))

(deftest userinfo-test
  (testing "no \"Authorization\" header in request should throw"
    (is (thrown? Exception (gcs/userinfo {:headers {}}))))
  (testing "fetching userinfo from request with \"Authorization\" header"
    (let [info (gcs/userinfo {:headers (once/get-auth-header)})]
      (is (:email info)))))

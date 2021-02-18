(ns wfl.integration.google.storage-test
  "Test the Google Cloud Storage namespace."
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [wfl.auth :as auth]
            [wfl.service.google.storage :as gcs]
            [wfl.tools.fixtures :as fixtures])
  (:import [java.util UUID]))

(deftest object-test
  (fixtures/with-temporary-cloud-storage-folder fixtures/gcs-test-bucket
    (fn [url]
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
          (is (gcs/copy-object bucket (str src-folder  "test")
                               bucket (str dest-folder "test"))))
        (testing "download"
          (let [local-file-name (str/join "-" ["wfl" "test" (UUID/randomUUID)])]
            (gcs/download-file local-file-name bucket (str dest-folder "test"))
            (try
              (is (= (slurp dockerfile) (slurp local-file-name)))
              (finally
                (io/delete-file local-file-name :silently)))))))))

(deftest userinfo-test
  (testing "no \"Authorization\" header in request should throw"
    (is (thrown? Exception (gcs/userinfo {:headers {}}))))
  (testing "fetching userinfo from request with \"Authorization\" header"
    (let [info (gcs/userinfo {:headers (auth/get-auth-header)})]
      (is (:email info)))))

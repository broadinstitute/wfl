(ns wfl.integration.datarepo-test
  (:require [clojure.data.json :as json]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [wfl.environment :as env]
            [wfl.service.datarepo :as datarepo]
            [wfl.service.google.storage :as gcs]
            [wfl.tools.fixtures :as fixtures])
  (:import [java.util UUID]))

;; UUIDs known to the Data Repo.
;;
(def dataset "f359303e-15d7-4cd8-a4c7-c50499c90252")
(def profile "390e7a85-d47f-4531-b612-165fc977d3bd")

(deftest delivery
  (fixtures/with-temporary-cloud-storage-folder fixtures/gcs-test-bucket
    (fn [url]
      (testing "delivery succeeds"
        (let [[bucket object] (gcs/parse-gs-url url)
              file-id     (UUID/randomUUID)
              vcf         (str file-id ".vcf")
              table       (str file-id ".tabular.json")
              vcf-url     (gcs/gs-url bucket (str object vcf))
              ingest-file (partial datarepo/ingest-file dataset profile)
              drsa        (get-in env/stuff [:debug :data-repo :service-account])]
          (letfn [(stage [file content]
                    (spit file content)
                    (gcs/upload-file file bucket (str object file))
                    (gcs/add-object-reader drsa bucket (str object file)))
                  (ingest [path vdest]
                    (let [job (ingest-file path vdest)]
                      (:fileId (datarepo/poll-job job))))
                  (cleanup []
                    (io/delete-file vcf)
                    (io/delete-file (str vcf ".tbi"))
                    (io/delete-file table))]
            (stage vcf "bogus vcf content")
            (stage (str vcf ".tbi") "bogus index content")
            (stage table (json/write-str
                          {:id        object
                           :vcf       (ingest vcf-url vcf)
                           :vcf_index (ingest vcf-url (str vcf ".tbi"))}
                          :escape-slash false))
            (let [table-url (gcs/gs-url bucket (str object table))
                  job       (datarepo/ingest-dataset dataset table-url "sample")
                  {:keys [bad_row_count row_count]} (datarepo/poll-job job)]
              (is (= 1 row_count))
              (is (= 0 bad_row_count)))
            (cleanup)))))))

(deftest test-create-dataset
  (-> (slurp "test/resources/datasets/assemble-refbased-outputs.json")
      json/read-str
      ;; give it a unique name to avoid collisions with other tests
      (update "name" #(str % (-> (UUID/randomUUID) (str/replace "-" ""))))
      (update "defaultProfileId" (constantly profile))
      (fixtures/with-temporary-dataset
        #(let [dataset (datarepo/dataset %)]
           (is (= % (:id dataset)))))))

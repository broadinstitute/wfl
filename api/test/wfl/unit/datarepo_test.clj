(ns wfl.unit.datarepo-test
  (:require [clojure.data.json     :as json]
            [clojure.java.io       :as io]
            [clojure.string        :as str]
            [clojure.test          :refer [deftest is testing]]
            [wfl.environments     :as env]
            [wfl.service.datarepo :as datarepo]
            [wfl.service.gcs      :as gcs]
            [wfl.tools.fixtures :refer [with-temporary-gcs-folder]]))

;; UUIDs known to the Data Repo.
;;
(def dataset "f359303e-15d7-4cd8-a4c7-c50499c90252")
(def profile "390e7a85-d47f-4531-b612-165fc977d3bd")

(deftest delivery
  (with-temporary-gcs-folder uri
    (testing "delivery succeeds"
      (let [[bucket object] (gcs/parse-gs-url uri)
            vcf "test.vcf"
            table "test.tabular.json"
            vcf-url (gcs/gs-url bucket (str object vcf))
            ingest-file (partial datarepo/file-ingest :gotc-dev dataset profile)
            drsa (get-in env/stuff [:debug :data-repo :service-account])]
        (letfn [(stage [file content]
                  (spit file content)
                  (gcs/upload-file file bucket (str object file))
                  (gcs/add-object-reader drsa bucket (str object file)))
                (ingest [path vdest]
                  (let [job (ingest-file path vdest)]
                    (:fileId (datarepo/poll-job :gotc-dev job))))
                (cleanup []
                  (io/delete-file vcf)
                  (io/delete-file (str vcf ".tbi"))
                  (io/delete-file table))]
          (stage vcf "bogus vcf content")
          (stage (str vcf ".tbi") "bogus index content")
          (stage table (json/write-str
                        {:id        bucket
                         :vcf       (ingest vcf-url vcf)
                         :vcf_index (ingest vcf-url (str vcf ".tbi"))}
                        :escape-slash false))
          (let [table-url (gcs/gs-url bucket (str object table))
                job (datarepo/tabular-ingest :gotc-dev dataset table-url "sample")
                {:keys [bad_row_count row_count]} (datarepo/poll-job :gotc-dev job)]
            (is (= 1 row_count))
            (is (= 0 bad_row_count)))
          (cleanup))))))

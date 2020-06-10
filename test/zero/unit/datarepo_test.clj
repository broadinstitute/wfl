(ns zero.unit.datarepo-test
  (:require [clojure.data.json     :as json]
            [clojure.java.io       :as io]
            [clojure.string        :as str]
            [clojure.test          :refer [deftest is testing]]
            [zero.environments     :as env]
            [zero.service.datarepo :as datarepo]
            [zero.service.gcs      :as gcs])
  (:import (java.util UUID)))

;; UUIDs known to the Data Repo.
;;
(def dataset "f359303e-15d7-4cd8-a4c7-c50499c90252")
(def profile "390e7a85-d47f-4531-b612-165fc977d3bd")

(def project
  "Run in this project."
  "broad-gotc-dev-storage")

(defonce unique
  #_"A string likely to be unique."
  (str/replace (str (UUID/randomUUID)) "-" ""))

(def bucket
  "A bucket name unlikely to exist already."
  (str/join "-" [(or (System/getenv "USER") "wfl") "test" unique]))

(defn make-day-bucket
  "Return a BUCKET that will live for 1 day."
  []
  (gcs/make-bucket   project bucket "US" "STANDARD")
  (gcs/patch-bucket! project bucket
                     {:lifecycle {:rule [{:action    {:type "Delete"}
                                          :condition {:age 1}}]}}))

(deftest delivery
  (testing "delivery succceeds"
    (let [vcf (str bucket ".vcf")
          table (str bucket ".tabular.json")
          vcf-url (gcs/gs-url bucket vcf)
          ingest-file (partial datarepo/file-ingest :gotc-dev dataset profile)
          drsa (get-in env/stuff [:debug :data-repo :service-account])]
      (letfn [(stage [file content]
                (spit file content)
                (gcs/upload-file file bucket file)
                (gcs/add-object-reader drsa bucket file))
              (ingest [path vdest]
                (let [job (ingest-file path vdest)]
                  (:fileId (datarepo/poll-job :gotc-dev job))))
              (cleanup []
                (gcs/delete-object bucket vcf)
                (gcs/delete-object bucket (str vcf ".tbi"))
                (gcs/delete-object bucket table)
                (gcs/delete-bucket bucket)
                (io/delete-file vcf)
                (io/delete-file (str vcf ".tbi"))
                (io/delete-file table))]
        (make-day-bucket)
        (stage vcf "bogus vcf content")
        (stage (str vcf ".tbi") "bogus index content")
        (stage table (json/write-str
                       {:id        bucket
                        :vcf       (ingest vcf-url vcf)
                        :vcf_index (ingest vcf-url (str vcf ".tbi"))}))
        (let [table-url (gcs/gs-url bucket table)
              job (datarepo/tabular-ingest :gotc-dev dataset table-url "sample")
              {:keys [bad_row_count row_count]} (datarepo/poll-job :gotc-dev job)]
          (is (= 1 row_count))
          (is (= 0 bad_row_count)))
        (cleanup)))))

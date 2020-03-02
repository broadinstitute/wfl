(ns zero.datarepo-test
  (:require [clojure.data.json     :as json]
            [clojure.java.io       :as io]
            [clojure.string        :as str]
            [clojure.test          :refer [deftest is testing]]
            [zero.debug            :as debug]
            [zero.environments     :as env]
            [zero.service.datarepo :as datarepo]
            [zero.service.gcs      :as gcs])
  (:import (java.util UUID)))

(deftest test-run
  (testing "delivery succceeds"
    (let [project "broad-gotc-dev"
          dataset-id "f359303e-15d7-4cd8-a4c7-c50499c90252"
          profile-id "390e7a85-d47f-4531-b612-165fc977d3bd"
          blame (or (System/getenv "USER") "zero")
          uid (str/replace (str (UUID/randomUUID)) "-" "")
          bucket (str/join "-" [blame "test" uid])
          datarepo-sa (get-in env/stuff [:gotc-dev :data-repo :service-account])
          vcf-file (debug/dump (str bucket ".vcf"))
          vcf-index-file (debug/dump (str bucket ".vcf.tbi"))
          tabular-json-file (debug/dump (str bucket ".tabular.json"))
          gcs-headers @datarepo/gcs-headers]
      (gcs/make-bucket project "US" "STANDARD" bucket)
      (letfn [(write-and-stage [file content]
                (spit file content)
                (gcs/upload-file file bucket file gcs-headers)
                (gcs/add-object-reader datarepo-sa bucket file gcs-headers))
              (ingest-file-ref [path vdest]
                (->> (datarepo/file-ingest :gotc-dev dataset-id profile-id path vdest)
                     (datarepo/poll-job :gotc-dev)
                     :fileId))
              (ingest-tabular-json [vcf-ref vcf-index-ref]
                (write-and-stage tabular-json-file
                                 (json/write-str {:id        (str bucket)
                                                  :vcf       vcf-ref
                                                  :vcf_index vcf-index-ref}))
                (->> (datarepo/tabular-ingest
                       :gotc-dev
                       dataset-id
                       (gcs/gs-url bucket tabular-json-file)
                       "sample")
                     (datarepo/poll-job :gotc-dev)))
              (cleanup []
                (gcs/delete-object bucket vcf-file gcs-headers)
                (gcs/delete-object bucket vcf-index-file gcs-headers)
                (gcs/delete-object bucket tabular-json-file gcs-headers)
                (gcs/delete-bucket bucket gcs-headers)
                (io/delete-file vcf-file)
                (io/delete-file vcf-index-file)
                (io/delete-file tabular-json-file))]
        (write-and-stage vcf-file "bogus vcf content")
        (write-and-stage vcf-index-file "bogus index content")
        (let [vcf-ref (ingest-file-ref (gcs/gs-url bucket vcf-file) vcf-file)
              index-ref (ingest-file-ref (gcs/gs-url bucket vcf-file) vcf-index-file)
              result (-> (ingest-tabular-json vcf-ref index-ref)
                         debug/dump)]
          (is (= (:row_count result) 1))
          (is (= (:bad_row_count result) 0)))
        (cleanup)))))

(comment
  (clojure.test/run-tests)
  )

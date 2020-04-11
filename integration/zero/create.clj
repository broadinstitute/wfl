(ns zero.create
  (:require [clojure.data.json :as json]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [zero.util :as util])
  (:import (java.util UUID)))

(def request
  (let [user (util/getenv "USER" "tbl")]
    {:creator  (str/join "@" [user "broadinstitute.org"])
     :cromwell "https://cromwell.gotc-dev.broadinstitute.org"
     :input    "gs://broad-gotc-test-storage/single_sample/plumbing/truth"
     :output   "gs://broad-gotc-dev-zero-test/wgs-test-output"
     :pipeline "ExternalWholeGenomeReprocessing"
     :project  (format "Testing with %s." user)
     :load     [{:unmapped_bam_suffix  ".unmapped.bam",
                 :sample_name          "NA12878 PLUMBING",
                 :base_file_name       "NA12878_PLUMBING",
                 :final_gvcf_base_name "NA12878_PLUMBING",
                 :input_cram           "develop/20k/NA12878_PLUMBING.cram"}]}))

(defn -main
  [& args]
  (let [url    "http://localhost:3000/api/v1/workload"
        tmp    (str "./" (UUID/randomUUID) ".json")
        auth   (str "Authorization: Bearer " (util/create-jwt :gotc-dev))
        noload (dissoc request :load)]
    (try
      (util/spit-json tmp request)
      (let [{:keys [id load pipeline uuid] :as response}
            (json/read-str
              (util/shell! "curl" "-H" auth
                           "-H" "Content-Type: application/json"
                           "--data-binary" (format "@%s" tmp) url)
              :key-fn keyword)
            [got]
            (json/read-str
              (util/shell! "curl" "-H" auth (str url "?uuid=" uuid))
              :key-fn keyword)]
        (assert (= noload (select-keys response (keys noload))))
        (assert (str/starts-with? load pipeline))
        (assert (str/ends-with?   load (str id)))
        (assert (= got response)))
      (finally (util/delete-tree (io/file tmp)))))
  (System/exit 0))

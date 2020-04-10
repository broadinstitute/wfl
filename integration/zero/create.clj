(ns zero.create
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [zero.debug :as debug]
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
  (let [url   "http://localhost:3000/api/v1/workload"
        jwt   (util/create-jwt :gotc-dev)
        load  (str/join "/" ["." (str (UUID/randomUUID) ".json")])
        tmp   (io/file load)]
    (try
      (io/make-parents tmp)
      (util/spit-json tmp request)
      (util/shell-io! "curl" "-X" "POST" #_"-v"
                      "-H" "Content-Type: application/json"
                      "-H" (format "Authorization: Bearer %s" jwt)
                      "--data-binary" (format "@%s" load)
                      url)
      (finally (util/delete-tree tmp)))))

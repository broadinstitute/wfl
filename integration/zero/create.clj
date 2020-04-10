(ns zero.test.create.workload
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [zero.util :as util])
  (:import (java.util UUID)))

(def request
  {:creator "tbl@broadinstitute.org"
   :cromwell "https://cromwell.gotc-dev.broadinstitute.org"
   :input    "gs://broad-gotc-test-storage/single_sample/plumbing/truth"
   :output   "gs://broad-gotc-dev-zero-test/wgs-test-output"
   :pipeline "ExternalWholeGenomeReprocessing"
   :project  "Testing with tbl"
   :load     [{:unmapped_bam_suffix  ".unmapped.bam",
               :sample_name          "NA12878 PLUMBING",
               :base_file_name       "NA12878_PLUMBING",
               :final_gvcf_base_name "NA12878_PLUMBING",
               :input_cram           "develop/20k/NA12878_PLUMBING.cram"}]})

(defn -main
  [& args]
  (let [url   "http://localhost:3000/api/v1/workload"
        jwt   (util/create-jwt :gotc-dev)
        tmp   (str/join "/" ["." (UUID/randomUUID)])
        load  (io/file tmp "wgs.json")
        token (io/file tmp "token.txt")]
    (try
      (io/make-parents token)
      (spit token jwt)
      (util/spit-json load request)
      (util/shell-io! ["curl" "-X" "POST"
                       "-H" "'Content-Type: application/json'"
                       "-H" (format "'Authorization: Bearer '$(<%s)" token)
                       "--data-binary" (format "@%s" load)
                       url])
      (finally (util/delete-tree tmp)))))

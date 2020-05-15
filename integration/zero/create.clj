(ns zero.create
  (:require [clojure.data.json :as json]
            [clojure.java.io :as io]
            [clojure.pprint :refer [pprint]]
            [clojure.string :as str]
            [zero.util :as util])
  (:import (java.util UUID)))

(def server
  "https://wfl-dot-broad-gotc-dev.appspot.com"
  "http://localhost:3000")

(def workload
  "Define a workload to start."
  (let [user (util/getenv "USER" "tbl")
        path "/single_sample/plumbing/truth"]
    {:creator  (str/join "@" [user "broadinstitute.org"])
     :cromwell "https://cromwell.gotc-dev.broadinstitute.org"
     :input    (str "gs://broad-gotc-test-storage" path)
     :output   (str "gs://broad-gotc-dev-zero-test/wgs-test-output" path)
     :pipeline "ExternalWholeGenomeReprocessing"
     :project  (format "Testing with %s." user)
     :items    [{:unmapped_bam_suffix  ".unmapped.bam",
                 :sample_name          "NA12878 PLUMBING",
                 :base_file_name       "NA12878_PLUMBING",
                 :final_gvcf_base_name "NA12878_PLUMBING",
                 :input_cram           "develop/20k/NA12878_PLUMBING.cram"}]}))

(defn -main
  [& args]
  (let [tmp      (str "./" (UUID/randomUUID) ".json")
        auth     (str "Authorization: Bearer " (util/create-jwt :gotc-dev))
        no-items (dissoc workload :items)]
    (try
      (util/spit-json tmp workload)
      (let [{:keys [id items pipeline uuid] :as response}
            (json/read-str
              (util/shell! "curl" "-H" auth
                           "-H" "Content-Type: application/json"
                           "--data-binary" (format "@%s" tmp)
                           (str server "/api/v1/create"))
              :key-fn keyword)
            [got]
            (json/read-str
              (util/shell! "curl" "-H" auth
                           (str server "/api/v1/workload?uuid=" uuid))
              :key-fn keyword)]
        (pprint [:got got])
        (assert (= no-items (select-keys response (keys no-items))))
        (assert (str/starts-with? items pipeline))
        (assert (str/ends-with?   items (str id)))
        (assert (= (select-keys got (keys response)) response))
        (assert (:workflows got)))
      (finally (util/delete-tree (io/file tmp)))))
  (System/exit 0))

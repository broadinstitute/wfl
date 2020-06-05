(ns zero.tools.workloads
  (:require [zero.module.wl :as wl]
            [zero.util :refer [shell!]]
            [zero.module.copyfile :as cp]))

(def git-branch (delay (shell! "git" "branch" "--show-current")))
(def git-email (delay (shell! "git" "config" "user.email")))

(def wl-workload
  "A whole genome sequencing workload used for testing."
  (let [path "/single_sample/plumbing/truth"]
    {:creator  @git-email
     :cromwell "https://cromwell.gotc-dev.broadinstitute.org"
     :input    (str "gs://broad-gotc-test-storage" path)
     :output   (str "gs://broad-gotc-dev-zero-test/wgs-test-output" path)
     :pipeline wl/pipeline
     :project  (format "(Test) %s" @git-branch)
     :items    [{:unmapped_bam_suffix  ".unmapped.bam",
                 :sample_name          "NA12878 PLUMBING",
                 :base_file_name       "NA12878_PLUMBING",
                 :final_gvcf_base_name "NA12878_PLUMBING",
                 :input_cram           "develop/20k/NA12878_PLUMBING.cram"}]}))

(defn mk-copyfile-workload
  "Make a workload to copy a file from SRC to DST"
  [src dst]
  {:creator  @git-email
   :cromwell "https://cromwell.gotc-dev.broadinstitute.org"
   :input    ""
   :output   ""
   :pipeline cp/pipeline
   :project  (format "(Test) %s" @git-branch)
   :items    [{:src src :dst dst}]})

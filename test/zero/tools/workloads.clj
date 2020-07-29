(ns zero.tools.workloads
  (:require [zero.environments :refer [stuff]]
            [zero.module.copyfile :as cp]
            [zero.module.wl :as wl]
            [zero.util :refer [shell!]]
            [zero.module.aou :as aou]))

(def git-branch (delay (shell! "git" "branch" "--show-current")))
(def git-email (delay (shell! "git" "config" "user.email")))

(def wgs-workload
  "A whole genome sequencing workload used for testing."
  (let [path "/single_sample/plumbing/truth"]
    {:creator  @git-email
     :cromwell (get-in stuff [:gotc-dev :cromwell :url])
     :input    (str "gs://broad-gotc-test-storage" path)
     :output   (str "gs://broad-gotc-dev-zero-test/wgs-test-output" path)
     :pipeline wl/pipeline
     :project  (format "(Test) %s" @git-branch)
     :items    [{:unmapped_bam_suffix  ".unmapped.bam",
                 :sample_name          "NA12878 PLUMBING",
                 :base_file_name       "NA12878_PLUMBING",
                 :final_gvcf_base_name "NA12878_PLUMBING",
                 :input_cram           "develop/20k/NA12878_PLUMBING.cram"}]}))

(defn aou-workload
  "An allofus arrays workload used for testing.
  Randomize it with IDENTIFIER for easier testing."
  [identifier]
  {:creator  @git-email
   :cromwell (get-in stuff [:gotc-dev :cromwell :url])
   :input    "aou-inputs-placeholder"
   :output   "aou-outputs-placeholder"
   :pipeline aou/pipeline
   :project  (format "(Test) %s %s" @git-branch identifier)
   :items    [{}]})

(defn make-copyfile-workload
  "Make a workload to copy a file from SRC to DST"
  [src dst]
  {:creator  @git-email
   :cromwell (get-in stuff [:gotc-dev :cromwell :url])
   :input    ""
   :output   ""
   :pipeline cp/pipeline
   :project  (format "(Test) %s" @git-branch)
   :items    [{:src src :dst dst}]})

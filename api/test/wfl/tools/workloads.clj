(ns wfl.tools.workloads
  (:require [wfl.environments :refer [stuff]]
            [wfl.module.copyfile :as cp]
            [wfl.module.wgs :as wgs]
            [wfl.util :refer [shell!]]
            [wfl.module.aou :as aou]))

(def git-branch (delay (shell! "git" "branch" "--show-current")))

(defn wgs-workload
  [identifier]
  "A whole genome sequencing workload used for testing."
  (let [path "/single_sample/plumbing/truth"]
    {:cromwell (get-in stuff [:gotc-dev :cromwell :url])
     :input    (str "gs://broad-gotc-dev-wfl-ptc-test-inputs" path)
     :output   (str "gs://broad-gotc-dev-wfl-ptc-test-outputs/wgs-test-output/" identifier)
     :pipeline wgs/pipeline
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
  {:cromwell (get-in stuff [:gotc-dev :cromwell :url])
   :input    "aou-inputs-placeholder"
   :output   "gs://broad-gotc-dev-wfl-ptc-test-outputs/aou-test-output"
   :pipeline aou/pipeline
   :project  (format "(Test) %s %s" @git-branch identifier)
   :items    [{}]})

(def aou-sample
  "An aou arrays sample for testing."
  {:cromwell      "https://cromwell-gotc-auth.gotc-dev.broadinstitute.org/",
   :notifications [{:chip_well_barcode           "7991775143_R01C01",
                    :bead_pool_manifest_file     "gs://broad-gotc-dev-wfl-ptc-test-inputs/arrays/metadata/HumanExome-12v1-1_A/HumanExome-12v1-1_A.bpm",
                    :analysis_version_number     1,
                    :call_rate_threshold         0.98
                    :extended_chip_manifest_file "gs://broad-gotc-dev-wfl-ptc-test-inputs/arrays/metadata/HumanExome-12v1-1_A/HumanExome-12v1-1_A.1.3.extended.csv",
                    :red_idat_cloud_path         "gs://broad-gotc-dev-wfl-ptc-test-inputs/arrays/HumanExome-12v1-1_A/idats/7991775143_R01C01/7991775143_R01C01_Red.idat",
                    :zcall_thresholds_file       "gs://broad-gotc-dev-wfl-ptc-test-inputs/arrays/metadata/HumanExome-12v1-1_A/IBDPRISM_EX.egt.thresholds.txt",
                    :reported_gender             "Female",
                    :cluster_file                "gs://broad-gotc-dev-wfl-ptc-test-inputs/arrays/metadata/HumanExome-12v1-1_A/HumanExomev1_1_CEPH_A.egt",
                    :sample_lsid                 "broadinstitute.org:bsp.dev.sample:NOTREAL.NA12878",
                    :sample_alias                "NA12878",
                    :green_idat_cloud_path       "gs://broad-gotc-dev-wfl-ptc-test-inputs/arrays/HumanExome-12v1-1_A/idats/7991775143_R01C01/7991775143_R01C01_Grn.idat",
                    :params_file                 "gs://broad-gotc-dev-wfl-ptc-test-inputs/arrays/HumanExome-12v1-1_A/inputs/7991775143_R01C01/params.txt",
                    :gender_cluster_file         "gs://broad-gotc-dev-wfl-ptc-test-inputs/arrays/metadata/HumanExome-12v1-1_A/HumanExomev1_1_gender.egt"}],
   :environment   "aou-dev",
   :uuid          nil})

(defn make-copyfile-workload
  "Make a workload to copy a file from SRC to DST"
  [src dst]
  {:cromwell (get-in stuff [:gotc-dev :cromwell :url])
   :input    ""
   :output   ""
   :pipeline cp/pipeline
   :project  (format "(Test) %s" @git-branch)
   :items    [{:src src :dst dst}]})

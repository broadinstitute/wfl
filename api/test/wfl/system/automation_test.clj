(ns wfl.system.automation-test
  (:require [clojure.test          :refer [deftest is]]
            [wfl.tools.fixtures    :as fixtures]
            [wfl.tools.resources   :as resources]
            [wfl.service.cromwell  :refer [final?]]
            [wfl.tools.endpoints   :as endpoints]
            [wfl.tools.workloads   :as workloads]))

(def firecloud-group        "workflow-launcher-dev")
(def method-configuration   "cdc-covid-surveillance/sarscov2_illumina_full")
(def snapshot-column        "run_date")
(def snapshot-readers       ["cdc-covid-surveillance@firecloud.org"])
(def source-dataset         "cd25d59e-1451-44d0-8a24-7669edb9a8f8")
(def source-table           "flowcells")
(def workspace-table        "flowcell")
(def workspace-to-clone     "wfl-dev/CDC_Viral_Sequencing")

(defn ^:private covid-workload-request
  "Build a covid workload request."
  [workspace]
  {:source   {:name      "TDR Snapshots"
              :snapshots ["f9242ab8-c522-4305-966d-7c51419377ab"]}
   :executor {:name                       "Terra"
              :workspace                  workspace
              :methodConfiguration        "wfl-dev/sarscov2_illumina_full"
              :methodConfigurationVersion 1
              :fromSource                 "importSnapshot"}
   :sink     {:name           "Terra Workspace"
              :workspace      workspace
              :entityType     "run_date"
              :identifier     "run_date"
              :fromOutputs    (resources/read-resource
                               "sarscov2_illumina_full/entity-from-outputs.edn")
              :skipValidation true}
   :project  @workloads/project
   :creator  @workloads/email
   :labels   ["hornet:test"]})

(deftest test-automate-sarscov2-illumina-full
  (fixtures/with-temporary-workspace-clone
    workspace-to-clone
    firecloud-group
    (fn [workspace]
      (let [finished? (comp final? :status)
            workload  (endpoints/create-workload
                       (covid-workload-request workspace))]
        (endpoints/start-workload workload)
        (workloads/when-finished
         #(is (every? finished? (endpoints/get-workflows %)))
         workload)))))

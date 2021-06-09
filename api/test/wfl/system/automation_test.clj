(ns wfl.system.automation-test
  (:require [clojure.pprint        :as pprint]
            [clojure.test          :refer [deftest is]]
            [wfl.environment       :as env]
            [wfl.tools.fixtures    :as fixtures]
            [wfl.tools.resources   :as resources]
            [wfl.tools.workflows   :as workflows]
            [wfl.service.firecloud :as firecloud]
            [wfl.util              :as util]
            [wfl.tools.endpoints   :as endpoints]
            [wfl.tools.workloads   :as workloads]))

(defn ^:private replace-urls-with-file-ids
  [file->fileid type value]
  (-> (fn [type value]
        (case type
          ("Boolean" "Float" "Int" "Number" "String") value
          "File" (file->fileid value)
          (throw (ex-info "Unknown type" {:type type :value value}))))
      (workflows/traverse type value)))

(def workspace-to-clone "wfl-dev/CDC_Viral_Sequencing")
(def firecloud-group "workflow-launcher-dev")
(def snapshot-readers ["cdc-covid-surveillance@firecloud.org"])
(def source-dataset "cd25d59e-1451-44d0-8a24-7669edb9a8f8")
(def source-table "flowcells")
(def snapshot-column "run_date")
(def source-dataset-profile "395f5921-d2d9-480d-b302-f856d787c9d9")
(def method-configuration "cdc-covid-surveillance/sarscov2_illumina_full")

(def workspace-table "flowcell")

(defn clone-workspace []
  (println "Cloning workspace" workspace-to-clone)
  (let [clone-name (util/randomize workspace-to-clone)]
    (firecloud/clone-workspace workspace-to-clone clone-name firecloud-group)
    (println "Cloned new workspace " clone-name)
    clone-name))

(defn delete-workspace [workspace]
  (println "Deleting" workspace)
  (firecloud/delete-workspace workspace))

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
  (let [tdr-profile (env/getenv "WFL_TDR_DEFAULT_PROFILE")]
    (fixtures/with-fixtures
      [(util/bracket clone-workspace delete-workspace)]
      (fn [[workspace]]
        (let [workload (endpoints/create-workload
                        (covid-workload-request workspace))]
          (endpoints/start-workload workload)
          (workloads/when-done
           (comp pprint/pprint endpoints/get-workflows)
           workload))))))

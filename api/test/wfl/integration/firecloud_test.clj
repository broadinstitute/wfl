(ns wfl.integration.firecloud-test
  (:require [clojure.test :refer [deftest is testing]]
            [wfl.service.cromwell :as cromwell]
            [wfl.service.firecloud :as firecloud]
            [wfl.tools.fixtures :as fixtures]))

(def firecloud-dev "https://firecloud-orchestration.dsde-dev.broadinstitute.org")

(def workspace "general-dev-billing-account/arrays")

(def method-configuration-name "Arrays")

(def method-configuration-namespace "general-dev-billing-account")

(def entity-type "sample")

(def entity-name "200598830050_R07C01-1")

(deftest test-terra-submission
  (testing "A workflow is created for the entity"
    (fixtures/with-temporary-environment {"WFL_FIRECLOUD_URL" firecloud-dev}
      #(let [submission-id (firecloud/create-submission
                            workspace
                            method-configuration-name
                            method-configuration-namespace
                            entity-type
                            entity-name)
             submission    (firecloud/get-submission workspace submission-id)
             workflow      (first (:workflows submission))]
         (is (= entity-name (get-in workflow [:workflowEntity :entityName])))))))

(defmacro ^:private using-assemble-refbased-workflow-bindings
  "Define a set of workflow bindings for use in `body`. The values refer to a
   workflow in the public COVID-19 surveillance workspace, used as an example."
  [& body]
  `(let [~'workspace "pathogen-genomic-surveillance/COVID-19_Broad_Viral_NGS"
         ~'submission "d0c5ff07-5b31-4e94-a075-fcefe92e57e6"
         ~'workflow "0099d8cc-e129-4656-8d83-7f5e1b16780e"
         ~'pipeline "assemble_refbased"
         ~'wdl {:path    "pipes/WDL/workflows/assemble_refbased.wdl"
                :release "master"
                :repo    "viral-pipelines"}]
     ~@body))

(deftest test-describe-wdl
  (using-assemble-refbased-workflow-bindings
   (let [description (firecloud/describe-wdl (cromwell/wdl-map->url wdl))]
     (is (:valid description))
     (is (empty? (:errors description)))
     (is (= pipeline (:name description)))
     (is (some? (:inputs description)))
     (is (some? (:outputs description))))))

(deftest test-get-workflow
  (using-assemble-refbased-workflow-bindings
   (let [wf (firecloud/get-workflow workspace submission workflow)]
     (is (= pipeline (:workflowName wf))))))

(deftest test-get-workflow-outputs
  (using-assemble-refbased-workflow-bindings
   (let [outputs (firecloud/get-workflow-outputs workspace submission workflow)]
     (is (some? (-> outputs :tasks ((keyword pipeline)) :outputs))))))

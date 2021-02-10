(ns wfl.integration.terra-test
  (:require [clojure.test :refer [deftest is testing]]
            [wfl.service.terra :as terra]
            [wfl.module.xx :as xx]
            [wfl.service.cromwell :as cromwell]))

(def terra-url
  "https://firecloud-orchestration.dsde-dev.broadinstitute.org")

(def workspace
  "general-dev-billing-account/arrays")

(def method-configuration-name
  "Arrays")

(def method-configuration-namespace
  "general-dev-billing-account")

(def entity-type
  "sample")

(def entity-name
  "200598830050_R07C01-1")

(deftest test-terra-submission
  (testing "A workflow is created for the entity"
    (let [submission-id (terra/create-submission terra-url workspace
                                                 method-configuration-name method-configuration-namespace
                                                 entity-type entity-name)
          submission (terra/get-submission terra-url workspace submission-id)
          workflow (first (:workflows submission))]
      (is (= entity-name (get-in workflow [:workflowEntity :entityName]))))))

(deftest test-describe-wdl
  (let [description (->> xx/workflow-wdl
                         cromwell/wdl-map->url
                         (terra/describe-wdl terra-url))]
    (is (:valid description))
    (is (empty? (:errors description)))
    (is (= xx/pipeline (:name description)))
    (is (some? (:inputs description)))
    (is (some? (:outputs description)))))

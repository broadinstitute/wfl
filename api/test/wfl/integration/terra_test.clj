(ns wfl.integration.terra-test
  (:require [clojure.test       :refer [deftest is testing]]
            [wfl.service.terra  :as terra]
            [wfl.tools.fixtures :refer [with-temporary-gcs-folder]]))

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

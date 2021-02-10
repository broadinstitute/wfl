(ns wfl.integration.terra-test
  (:require [clojure.test :refer [deftest is testing]]
            [wfl.service.terra :as terra]
            [wfl.module.xx :as xx]
            [wfl.service.cromwell :as cromwell]
            [clojure.pprint :as pprint]
            [clojure.string :as str]))

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
          submission    (terra/get-submission terra-url workspace submission-id)
          workflow      (first (:workflows submission))]
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

(defmacro ^:private using-assemble-refbased-workflow-bindings
  "Define a set of workflow bindings for use in `body`. The values refer to a
   workflow in the public COVID-19 surveillance workspace, used as an example."
  [& body]
  `(let [~'firecloud "https://api.firecloud.org/"
         ~'workspace "pathogen-genomic-surveillance/COVID-19_Broad_Viral_NGS"
         ~'submission "d0c5ff07-5b31-4e94-a075-fcefe92e57e6"
         ~'workflow "0099d8cc-e129-4656-8d83-7f5e1b16780e"
         ~'pipeline "assemble_refbased"
         ~'wdl {:path    "pipes/WDL/workflows/assemble_refbased.wdl"
                :release "master"
                :repo    "viral-pipelines"}]
     ~@body))

(deftest test-get-workflow
  (using-assemble-refbased-workflow-bindings
   (let [wf (terra/get-workflow firecloud workspace submission workflow)]
     (is (= pipeline (:workflowName wf))))))

(deftest test-get-workflow-outputs
  (using-assemble-refbased-workflow-bindings
   (let [outputs (terra/get-workflow-outputs firecloud workspace submission workflow)]
     (is (some? (-> outputs :tasks ((keyword pipeline)) :outputs))))))

(deftest test-outputs-type-dispatch
  (letfn [(go! [type name value]
            (case (:typeName type)
              "File"     (is (str/starts-with? value "gs://"))
              "Optional" (when value (go! (:optionalType type) name value))
              "Array"    (run! (partial go! (:arrayType type) name) value)
              "String"   (is (string? value) (str name " is not a String"))
              nil        (is false (str "No type found for " name))
              (is (number? value))))
          (make-type-entry [pipeline {:keys [name valueType]}]
            {(keyword (str pipeline "." name)) valueType})
          (make-type-environment [firecloud pipeline wdl]
            (->> (terra/describe-wdl firecloud (cromwell/wdl-map->url wdl))
                 :outputs
                 (map (partial make-type-entry pipeline))
                 (into {})))]
    (using-assemble-refbased-workflow-bindings
     (let [name->type (make-type-environment firecloud pipeline wdl)
           outputs    (-> (terra/get-workflow-outputs firecloud workspace submission workflow)
                          :tasks
                          (get (keyword pipeline))
                          :outputs)]
       (run! (fn [[name value]] (go! (name->type name) name value)) outputs)))))

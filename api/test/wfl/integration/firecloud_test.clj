(ns wfl.integration.firecloud-test
  (:require [clojure.test :refer [deftest is testing]]
            [wfl.service.cromwell :as cromwell]
            [wfl.service.firecloud :as firecloud]
            [wfl.util :as util]))

;; Of the form NAMESPACE/NAME
(def workspace "wfl-dev/Illumina-Genotyping-Array")

;; Of the form NAMESPACE/NAME
(def method-configuration "warp-pipelines/IlluminaGenotypingArray")

;; An entity is a pair [Type Name]
(def entity ["sample" "NA12878"])

;; A submission that was manually created
(def well-known-submission "12ea8b91-737d-4838-972e-05f3c80f3881")
(def well-known-workflow   "1d5c211a-810f-49c5-be3a-e9502d7828d1")

(deftest test-get-submission
  (let [submission (firecloud/get-submission workspace well-known-submission)]
    (is (= "Done" (:status submission)))
    (let [[workflow & rest] (:workflows submission)]
      (is (empty? rest))
      (is (= well-known-workflow (:workflowId workflow)))
      (is (#{"Succeeded"} (:status workflow))))))

(deftest test-create-submission
  (util/bracket
   #(firecloud/create-submission workspace method-configuration entity)
   #(firecloud/abort-submission workspace %)
   #(let [workflow (-> (firecloud/get-submission workspace %) :workflows first)]
      (is (= (second entity) (get-in workflow [:workflowEntity :entityName])))
      (is (#{"Queued" "Submitted"}
            ;; GH-1212: `get-submission` can return the workflow before it has
            ;; been queued. In such cases :status is nil so try again
           (or (:status workflow) (-> (firecloud/get-submission workspace %)
                                      :workflows
                                      first
                                      :status)))))))
;; TODO: tests for 0 and 1 entities.
(deftest test-create-submissions
  (util/bracket
    #(firecloud/create-submissions workspace method-configuration entity entity entity)
    #(doseq [submission-id %]
       (firecloud/abort-submission workspace submission-id))
    #(doseq [submission-id %]
       (let [workflow (-> (firecloud/get-submission workspace submission-id) :workflows first)]
         (is (= (second entity) (get-in workflow [:workflowEntity :entityName])))
         (is (#{"Queued" "Submitted"}
              ;; GH-1212: `get-submission` can return the workflow before it has
              ;; been queued. In such cases :status is nil so try again
              (or (:status workflow) (-> (firecloud/get-submission workspace submission-id)
                                         :workflows
                                         first
                                         :status))))))))

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

(deftest test-describe-workflow-url
  (using-assemble-refbased-workflow-bindings
   (let [description (firecloud/describe-workflow (cromwell/wdl-map->url wdl))]
     (is (:valid description))
     (is (empty? (:errors description)))
     (is (= pipeline (:name description)))
     (is (some? (:inputs description)))
     (is (some? (:outputs description))))))

(deftest test-describe-workflow-source
  (let [description (firecloud/describe-workflow
                     (slurp "resources/wdl/copyfile.wdl"))]
    (is (:valid description))
    (is (empty? (:errors description)))
    (is (= "copyfile" (:name description)))
    (is (some? (:inputs description)))
    (is (empty? (:outputs description)))))

(deftest test-get-workflow
  (using-assemble-refbased-workflow-bindings
   (let [wf (firecloud/get-workflow workspace submission workflow)]
     (is (= pipeline (:workflowName wf))))))

(deftest test-get-workflow-outputs
  (using-assemble-refbased-workflow-bindings
   (let [outputs (firecloud/get-workflow-outputs workspace submission workflow)]
     (is (some? (-> outputs :tasks ((keyword pipeline)) :outputs))))))

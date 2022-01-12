(ns wfl.integration.firecloud-test
  (:require [clojure.string        :as str]
            [clojure.test          :refer [deftest is testing]]
            [wfl.service.cromwell  :as cromwell]
            [wfl.service.firecloud :as firecloud]
            [wfl.util              :as util])
  (:import [java.util UUID]))

;; Of the form NAMESPACE/NAME
(def workspace "wfl-dev/Illumina-Genotyping-Array")

;; Of the form NAMESPACE/NAME
(def method-configuration "warp-pipelines/IlluminaGenotypingArray")

;; An entity is a pair [Type Name]
(def entity ["sample" "NA12878"])
(def entity-set-type (str (first entity) "_set"))

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
   (comp :submissionId #(firecloud/create-submission workspace method-configuration entity))
   #(firecloud/abort-submission workspace %)
   #(let [get-workflows (fn [] (:workflows (firecloud/get-submission workspace %)))
          workflow      (first (get-workflows))]
      (is (= (second entity) (get-in workflow [:workflowEntity :entityName])))
      (is (not (str/blank? (util/poll (comp :status first get-workflows))))))))

(deftest test-create-submissions-for-entity-set
  (testing "Empty entity list throws AssertionError"
    (is (thrown? AssertionError
                 (firecloud/create-submission-for-entity-set workspace
                                                             method-configuration
                                                             []))))
  (doseq [entity-count (range 1 4)]
    (testing (str "Submission with " entity-count " matching entit(ies) yields 1 workflow")
      (let [entity-set-name (str (UUID/randomUUID))]
        (util/bracket
         (comp :submissionId
               #(firecloud/create-submission-for-entity-set
                 workspace
                 method-configuration
                 (repeat entity-count entity)
                 entity-set-name))
         #((firecloud/abort-submission workspace %)
           (firecloud/delete-entities workspace [[entity-set-type entity-set-name]]))
         #(let [get-workflows     (fn [] (:workflows (firecloud/get-submission workspace %)))
                [workflow & rest] (get-workflows)]
            (is (empty? rest))
            (is (= (second entity) (get-in workflow [:workflowEntity :entityName])))
            (is (not (str/blank? (util/poll (comp :status first get-workflows)))))))))))

(defn ^:private with-entity?
  "Check if entity name is found in HTTP response."
  [name] (fn [response] (= (:name response) name)))

(defn ^:private matches-entity?
  "Check if entity reference [Type Name] matches item from HTTP response."
  [[ref-type ref-name]] (fn [{response-type :entityType response-name :entityName}]
                          (and (= ref-type response-type) (= ref-name response-name))))

(deftest test-import-entity-set
  (doseq [entity-count (range 1 4)]
    (testing (str "Import entity set with " entity-count " matching entit(ies)")
      (let [entity-set-name (str (UUID/randomUUID))]
        (util/bracket
         #(-> (firecloud/import-entity-set workspace entity-set-name (repeat entity-count entity))
              :body)
         #(firecloud/delete-entities workspace [[% entity-set-name]])
         #(let [[entity-response & rest] (->> (firecloud/list-entities workspace %)
                                              (filter (with-entity? entity-set-name)))
                items (get-in entity-response [:attributes :samples :items])]
            (is (empty? rest))
            (is (= entity-count (count items)))
            (is (every? (matches-entity? entity) items))))))))

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
   (let [wf        (firecloud/get-workflow workspace submission workflow)
         wf-status (firecloud/get-workflow workspace submission workflow "status")]
     (is (= pipeline (:workflowName wf)))
     (is (= "Succeeded" (:status wf)))
     (is (empty? (:workflowName wf-status))
         "Workflow fetch specifying include-key should have keys restricted")
     (is (= "Succeeded" (:status wf-status))))))

(deftest test-get-workflow-outputs
  (using-assemble-refbased-workflow-bindings
   (let [outputs (firecloud/get-workflow-outputs workspace submission workflow)]
     (is (some? (-> outputs :tasks ((keyword pipeline)) :outputs))))))

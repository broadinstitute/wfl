(ns wfl.integration.firecloud-test
  (:require [clojure.string        :as str]
            [clojure.test          :refer [deftest is testing]]
            [wfl.service.cromwell  :as cromwell]
            [wfl.service.firecloud :as firecloud]
            [wfl.util              :as util]))

;; Of the form NAMESPACE/NAME
(def workspace "wfl-dev/Illumina-Genotyping-Array")

(def pipeline "IlluminaGenotypingArray")
;; Of the form NAMESPACE/NAME
(def method-configuration (str "warp-pipelines/" pipeline))

;; An entity is a pair [Type Name]
(def entity ["sample" "NA12878"])
(def entity-set-type (str (first entity) "_set"))

;; A manually-triggered submission and its workflow
;; (If this gets archived, a new copy can be resubmitted at
;; https://app.terra.bio/#workspaces/wfl-dev/Illumina-Genotyping-Array/workflows/warp-pipelines/IlluminaGenotypingArray)
(def submission-id "ffdf72b0-6014-4f18-9c35-18eab21ac049")
(def workflow-id   "1a1c8089-9d84-4d85-b104-2be5092a3604")

(deftest test-get-submission
  (let [submission (firecloud/get-submission workspace submission-id)]
    (is (= "Done" (:status submission)))
    (let [[workflow & rest] (:workflows submission)]
      (is (empty? rest))
      (is (= workflow-id (:workflowId workflow)))
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
      (let [entity-set-name (str (random-uuid))]
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
      (let [entity-set-name (str (random-uuid))]
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

(deftest test-describe-workflow-url
  (let [description
        (-> {:path    "pipelines/broad/genotyping/illumina/IlluminaGenotypingArray.wdl"
             :release "IlluminaGenotypingArray_v1.11.0"
             :repo    "warp"}
            cromwell/wdl-map->url
            firecloud/describe-workflow)]
    (is (:valid description))
    (is (empty? (:errors description)))
    (is (= pipeline (:name description)))
    (is (some? (:inputs description)))
    (is (some? (:outputs description)))))

(deftest test-describe-workflow-source
  (let [description (firecloud/describe-workflow
                     (slurp "resources/wdl/copyfile.wdl"))]
    (is (:valid description))
    (is (empty? (:errors description)))
    (is (= "copyfile" (:name description)))
    (is (some? (:inputs description)))
    (is (empty? (:outputs description)))))

(deftest test-get-workflow
  (let [wf        (firecloud/get-workflow workspace submission-id workflow-id)
        wf-status (firecloud/get-workflow workspace submission-id workflow-id "status")]
    (when (:metadataArchiveStatus wf)
      (throw (ex-info "Need to reference a newer unarchived workflow in tests"
                      {:workflow wf})))
    (is (= pipeline (:workflowName wf)))
    (is (= "Succeeded" (:status wf)))
    (is (empty? (:workflowName wf-status))
        "Workflow fetch specifying include-key should have keys restricted")
    (is (= "Succeeded" (:status wf-status)))))

(deftest test-get-workflow-outputs
  (let [outputs (firecloud/get-workflow-outputs workspace submission-id workflow-id)]
    (is (some? (-> outputs :tasks ((keyword pipeline)) :outputs)))))

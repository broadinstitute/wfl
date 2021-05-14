(ns wfl.integration.modules.arrays-test
  (:require [clojure.test :refer [testing is deftest use-fixtures]]
            [wfl.integration.modules.shared :as shared]
            [wfl.service.firecloud :as terra]
            [wfl.tools.fixtures :as fixtures]
            [wfl.tools.workloads :as workloads])
  (:import [java.util UUID]))

(use-fixtures
  :once
  fixtures/temporary-postgresql-database
  (fixtures/temporary-environment
   {"WFL_FIRECLOUD_URL" "https://firecloud-orchestration.dsde-dev.broadinstitute.org"}))

(defn ^:private mock-terra-create-submission [& _]
  {:submissionId (UUID/randomUUID)})

(defn ^:private mock-get-workflow-status-by-entity [& _] "Succeeded")

(defn ^:private make-arrays-workload-request []
  (-> (workloads/arrays-workload-request (UUID/randomUUID))
      (assoc :creator @workloads/email)))

(defn ^:private check-inputs
  [workflow]
  (is (contains? (:inputs workflow) :entity-type))
  (is (contains? (:inputs workflow) :entity-name))
  (is (:inputs workflow) "Inputs are under :inputs")
  (is
   (not-any? (partial contains? workflow) (keys workloads/arrays-sample-terra))
   "Inputs are not at the top-level"))

(defn ^:private check-workflow
  [workflow]
  (is (:uuid workflow))
  (is (:status workflow))
  (is (:updated workflow)))

(deftest test-create-arrays-workload!
  (let [workload (-> (make-arrays-workload-request)
                     workloads/create-workload!)]
    (run! check-inputs (workloads/workflows workload))))

(deftest test-workload-state-transition
  (with-redefs-fn
    {#'terra/create-submission             mock-terra-create-submission
     #'terra/get-workflow-status-by-entity mock-get-workflow-status-by-entity}
    #(shared/run-workload-state-transition-test!
      (make-arrays-workload-request))))

(deftest test-stop-workload-state-transition
  (shared/run-stop-workload-state-transition-test!
   (make-arrays-workload-request)))

(deftest test-start-arrays-workload!
  (with-redefs-fn {#'terra/create-submission mock-terra-create-submission}
    #(let [workload (-> (make-arrays-workload-request)
                        workloads/create-workload!
                        workloads/start-workload!)]
       (is (:started workload))
       (let [workflows (workloads/workflows workload)]
         (run! check-inputs workflows)
         (run! check-workflow (workflows))))))

(deftest test-exec-arrays-workload!
  (with-redefs-fn {#'terra/create-submission mock-terra-create-submission}
    #(let [workload (-> (make-arrays-workload-request)
                        workloads/execute-workload!)]
       (is (:started workload))
       (let [workflows (workloads/workflows workload)]
         (run! check-inputs workflows)
         (run! check-workflow (workflows))))))

(deftest test-update-arrays-workload!
  (letfn [(check-status [status workflow]
            (is (= status (:status workflow))))]
    (with-redefs-fn {#'terra/get-workflow-status-by-entity mock-get-workflow-status-by-entity}
      #(let [workload (-> (make-arrays-workload-request)
                          workloads/execute-workload!)]
         (run! (partial check-status "Submitted") (workloads/workflows workload))
         (let [updated-workload (workloads/update-workload! workload)]
           (run! (partial check-status "Succeeded") (workloads/workflows updated-workload))
           (is (:finished updated-workload)))))))

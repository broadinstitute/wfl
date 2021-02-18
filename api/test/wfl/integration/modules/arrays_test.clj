(ns wfl.integration.modules.arrays-test
  (:require [clojure.test :refer [testing is deftest use-fixtures]]
            [wfl.api.spec]
            [wfl.service.terra :as terra]
            [wfl.tools.fixtures :as fixtures]
            [wfl.tools.workloads :as workloads])
  (:import (java.util UUID)))

(use-fixtures :once fixtures/temporary-postgresql-database)

(defn ^:private mock-terra-create-submission [& _]
  (UUID/randomUUID))

(defn ^:private mock-get-workflow-status-by-entity [& _]
  "Succeeded")

(defn ^:private make-arrays-workload-request []
  (-> (workloads/arrays-workload-request (UUID/randomUUID))
      (assoc :creator (:email @workloads/userinfo))))

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
    (run! check-inputs (:workflows workload))))

(deftest test-update-unstarted
  (let [workload (->> (make-arrays-workload-request)
                      workloads/create-workload!
                      workloads/update-workload!)]
    (is (nil? (:finished workload)))
    (is (nil? (:submitted workload)))))

(deftest test-start-arrays-workload!
  (with-redefs-fn {#'terra/create-submission mock-terra-create-submission}
    #(let [workload (-> (make-arrays-workload-request)
                        workloads/create-workload!
                        workloads/start-workload!)]
       (is (:started workload))
       (run! check-inputs (:workflows workload))
       (run! check-workflow (:workflows workload)))))

(deftest test-exec-arrays-workload!
  (with-redefs-fn {#'terra/create-submission mock-terra-create-submission}
    #(let [workload (-> (make-arrays-workload-request)
                        workloads/execute-workload!)]
       (is (:started workload))
       (run! check-inputs (:workflows workload))
       (run! check-workflow (:workflows workload)))))

(deftest test-update-arrays-workload!
  (letfn [(check-status [status workflow]
            (is (= status (:status workflow))))]
    (with-redefs-fn {#'terra/get-workflow-status-by-entity mock-get-workflow-status-by-entity}
      #(let [workload (-> (make-arrays-workload-request)
                          workloads/execute-workload!)]
         (run! (partial check-status "Submitted") (:workflows workload))
         (let [updated-workload (workloads/update-workload! workload)]
           (run! (partial check-status "Succeeded") (:workflows updated-workload))
           (is (:finished updated-workload)))))))

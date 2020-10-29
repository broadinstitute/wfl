(ns wfl.integration.modules.wgs-test
  (:require [clojure.test :refer [deftest testing is] :as clj-test]
            [wfl.service.cromwell :refer [wait-for-workflow-complete submit-workflow]]
            [wfl.tools.endpoints :as endpoints]
            [wfl.tools.fixtures :as fixtures]
            [wfl.tools.workloads :as workloads]
            [wfl.module.wgs :as wgs]
            [wfl.util :as util])
  (:import (java.util UUID)))

(clj-test/use-fixtures :once fixtures/temporary-postgresql-database)

(defn ^:private mock-really-submit-one-workflow
  [& _]
  (UUID/randomUUID))

(defn ^:private make-wgs-workload-request
  []
  (-> (workloads/wgs-workload-request (UUID/randomUUID))
      (assoc :creator (:email @endpoints/userinfo))))

(deftest start-wgs-workload!
  (with-redefs-fn {#'wgs/really-submit-one-workflow mock-really-submit-one-workflow}
    #(let [workload (-> (make-wgs-workload-request)
                      workloads/create-workload!
                      workloads/start-workload!)]
       (letfn [(check-nesting [workflow]
                 (is
                   (= (:inputs workflow) workloads/wgs-inputs)
                   "Inputs are under :inputs")
                 (is
                   (not-any? (partial contains? workflow) (keys workloads/wgs-inputs))
                   "Inputs are not at the top-level"))]
         (run! check-nesting (:workflows workload))))))

(deftest test-workflow-options
  (let [option-sequence [:a :a :b]
        workload-request (-> (make-wgs-workload-request)
                             (update :items (fn [existing]
                                              (mapv #(assoc %1 :workflow_options {%2 "some value"})
                                                    (repeat (first existing)) option-sequence)))
                             (assoc :workflow_options {:c "some other value"}))
        submitted-option-counts (atom {})
        ;; Mock cromwell/submit-workflow, count observed option keys per workflow
        pretend-submit (fn [_ _ _ _ options _]
                         (run! #(swap! submitted-option-counts update % (fnil inc 0))
                               (keys options))
                         (str (UUID/randomUUID)))]
    (with-redefs-fn {#'submit-workflow pretend-submit}
      #(-> workload-request
           workloads/execute-workload!
           (as-> workload
                 (testing "Options in server response"
                   (is (get-in workload [:workflow_options :c]))
                   (is (= (count option-sequence)
                          (count (filter (fn [w] (get-in w [:workflow_options :c])) (:workflows workload)))))
                   (is (= (count (filter (partial = :a) option-sequence))
                          (count (filter (fn [w] (get-in w [:workflow_options :a])) (:workflows workload)))))
                   (is (= (count (filter (partial = :b) option-sequence))
                          (count (filter (fn [w] (get-in w [:workflow_options :b])) (:workflows workload)))))
                   (is (workloads/baseline-options-across-workload
                         (util/make-options (wgs/get-cromwell-wgs-environment (:cromwell workload)))
                         workload))))))
    (testing "Options sent to Cromwell"
      (is (= (count option-sequence)
             (:c @submitted-option-counts)))
      (is (= (count (filter (partial = :a) option-sequence))
             (:a @submitted-option-counts)))
      (is (= (count (filter (partial = :b) option-sequence))
             (:b @submitted-option-counts))))))

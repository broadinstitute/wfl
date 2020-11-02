(ns wfl.integration.modules.copyfile-test
  (:require [clojure.test :refer [deftest testing is] :as clj-test]
            [wfl.service.cromwell :refer [wait-for-workflow-complete submit-workflow]]
            [wfl.tools.endpoints :as endpoints]
            [wfl.tools.fixtures :as fixtures]
            [wfl.tools.workloads :as workloads]
            [wfl.util :as util]
            [wfl.module.all :as all])
  (:import (java.util UUID)))

(clj-test/use-fixtures :once fixtures/temporary-postgresql-database)

(defn ^:private make-copyfile-workload-request
  [src dst]
  (-> (workloads/copyfile-workload-request src dst)
      (assoc :creator (:email @endpoints/userinfo))))

(deftest test-workflow-options
  (fixtures/with-temporary-gcs-folder
    uri
    (let [src (str uri "input.txt")
          dst (str uri "output.txt")
          option-sequence [:a :a :b]
          workload-request (-> (make-copyfile-workload-request src dst)
                               (update :items (fn [existing]
                                                (mapv #(assoc %1 :workflow_options {%2 "some value"})
                                                      (repeat (first existing)) option-sequence)))
                               (assoc :common {:workflow_options {:c "some other value"}}))
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
                     (is (get-in workload [:common :workflow_options :c]))
                     (is (= (count option-sequence)
                            (count (filter (fn [w] (get-in w [:workflow_options :c])) (:workflows workload)))))
                     (is (= (count (filter (partial = :a) option-sequence))
                            (count (filter (fn [w] (get-in w [:workflow_options :a])) (:workflows workload)))))
                     (is (= (count (filter (partial = :b) option-sequence))
                            (count (filter (fn [w] (get-in w [:workflow_options :b])) (:workflows workload)))))
                     (is (workloads/baseline-options-across-workload
                           (-> (:cromwell workload)
                               all/cromwell-environments
                               first
                               util/make-options)
                           workload))))))
      (testing "Options sent to Cromwell"
        (is (= (count option-sequence)
               (:c @submitted-option-counts)))
        (is (= (count (filter (partial = :a) option-sequence))
               (:a @submitted-option-counts)))
        (is (= (count (filter (partial = :b) option-sequence))
               (:b @submitted-option-counts)))))))

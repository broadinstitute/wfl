(ns wfl.integration.modules.aou-test
  (:require [clojure.spec.alpha :as s]
            [clojure.test :refer [testing is deftest use-fixtures]]
            [wfl.api.spec]
            [wfl.module.aou :as aou]
            [wfl.service.cromwell :refer [submit-workflow]]
            [wfl.service.postgres :as postgres]
            [wfl.tools.fixtures :as fixtures]
            [wfl.tools.workloads :as workloads]
            [wfl.tools.endpoints :as endpoints])
  (:import (java.util UUID)))

(use-fixtures :once fixtures/temporary-postgresql-database)

(defn mock-submit-workload [& _] (UUID/randomUUID))
(def mock-cromwell-status (constantly "Succeeded"))

(defn ^:private make-aou-workload-request []
  (-> (workloads/aou-workload-request (UUID/randomUUID))
    (assoc :creator (:email @endpoints/userinfo))))

(defn ^:private inc-version [sample]
  (update sample :analysis_version_number inc))

(def count=1? (comp (partial == 1) count))

(deftest test-append-to-aou
  (with-redefs-fn {#'aou/submit-aou-workflow mock-submit-workload}
    #(let [workload            (workloads/execute-workload! (make-aou-workload-request))
           append-to-workload! (fn [xs] (workloads/append-to-workload! xs workload))]
       (testing "appending a sample to the workload"
         (let [response (append-to-workload! [workloads/aou-sample])]
           (is (s/valid? :wfl.api.spec/append-to-aou-response response))
           (is (count=1? response))))
       (testing "appending the same sample to the workload does nothing"
         (is (empty? (append-to-workload! [workloads/aou-sample]))))
       (testing "incrementing analysis_version_number starts a new workload"
         (is (count=1? (append-to-workload! [(inc-version workloads/aou-sample)]))))
       (testing "only one workflow is started when there are multiple duplicates"
         (is (count=1?
               (append-to-workload!
                 (repeat 5 (inc-version (inc-version workloads/aou-sample)))))))
       (testing "appending empty workload"
         (let [response (append-to-workload! [])]
           (is (s/valid? :wfl.api.spec/append-to-aou-response response))
           (is (empty? response)))))))

(deftest test-append-to-aou-not-started
  (with-redefs-fn {#'aou/submit-aou-workflow mock-submit-workload}
    #(is
       (thrown? Exception
         (workloads/append-to-workload! [workloads/aou-sample]
           (workloads/create-workload! (make-aou-workload-request)))))))

(deftest test-aou-cannot-be-stopped!
  (with-redefs-fn {#'aou/submit-aou-workflow  mock-submit-workload
                   #'postgres/cromwell-status mock-cromwell-status}
    #(let [workload (-> (make-aou-workload-request)
                      (workloads/execute-workload!)
                      (workloads/update-workload!))]
       (is (not (:finished workload)))
       (workloads/append-to-workload! [workloads/aou-sample] workload)
       (let [workload (workloads/load-workload-for-uuid (:uuid workload))]
         (is (every? (comp #{"Submitted"} :status) (:workflows workload)))
         (let [workload (workloads/update-workload! workload)]
           (is (every? (comp #{"Succeeded"} :status) (:workflows workload)))
           (is (not (:finished workload))))))))

(deftest test-workflow-options
  (let [workload (-> (make-aou-workload-request)
                     (assoc :workflow_options {:a "some value"})
                     workloads/execute-workload!)
        append-to-workload! (fn [xs] (workloads/append-to-workload! xs workload))
        submitted-option-counts (atom {})
        ;; Mock cromwell/submit-workflow, count observed option keys per workflow
        pretend-submit (fn [_ _ _ _ options _]
                         (run! #(swap! submitted-option-counts update % (fnil inc 0))
                               (keys options))
                         (str (UUID/randomUUID)))]
    (with-redefs-fn {#'submit-workflow          pretend-submit
                     #'postgres/cromwell-status mock-cromwell-status}
      #(do (testing "Options in initial server response"
             (is (get-in workload [:workflow_options :a]))
             (is (workloads/baseline-options-across-workload
                   aou/default-options
                   workload)))
           (append-to-workload! [workloads/aou-sample])
           (testing "Options in subsequent server response"
             (let [response (workloads/update-workload! workload)]
               (is (get-in response [:workflow_options :a]))
               (is (workloads/baseline-options-across-workload
                     aou/default-options
                     response))
               (run! (fn [opts]
                       (is (contains? opts :a))
                       ;; :final_workflow_outputs_dir is special because it is a WFL-calculated per-workflow option
                       (is (contains? opts :final_workflow_outputs_dir)))
                     (map :workflow_options (:workflows response)))))))
    (testing "Options sent to Cromwell"
      (is (= 1
             (:a @submitted-option-counts)
             (:final_workflow_outputs_dir @submitted-option-counts))))))

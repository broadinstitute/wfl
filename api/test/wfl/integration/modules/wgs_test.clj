(ns wfl.integration.modules.wgs-test
  (:require [clojure.set :refer [rename-keys]]
            [clojure.test :refer [deftest testing is] :as clj-test]
            [wfl.service.cromwell :refer [wait-for-workflow-complete submit-workflow]]
            [wfl.tools.endpoints :as endpoints]
            [wfl.tools.fixtures :as fixtures]
            [wfl.tools.workloads :as workloads]
            [wfl.module.wgs :as wgs]
            [wfl.jdbc :as jdbc]
            [wfl.module.all :as all]
            [clojure.string :as str]
            [wfl.util :as util]
            [clojure.data.json :as json]
            [wfl.api.common :as common])
  (:import (java.util UUID)))

(clj-test/use-fixtures :once fixtures/temporary-postgresql-database)

(defn ^:private mock-really-submit-one-workflow [& _]
  (UUID/randomUUID))

(defn ^:private make-wgs-workload-request []
  (-> (UUID/randomUUID)
    workloads/wgs-workload-request
    (assoc :creator (:email @endpoints/userinfo))))

(deftest test-start-wgs-workload!
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

(defn ^:private old-create-wgs-workload! []
  (let [request (make-wgs-workload-request)]
    (jdbc/with-db-transaction [tx (fixtures/testing-db-config)]
      (let [[id table] (all/add-workload-table! tx wgs/workflow-wdl request)
            add-id (fn [m id] (assoc (:inputs m) :id id))]
        (jdbc/insert-multi! tx table (map add-id (:items request) (range)))
        (jdbc/update! tx :workload {:version "0.3.8"} ["id = ?" id])
        id))))

(deftest test-loading-old-wgs-workload
  (testing "loading a wgs workload saved in a previous release"
    (let [id (old-create-wgs-workload!)
          workload (workloads/load-workload-for-id id)]
      (is (= id (:id workload)))
      (is (= wgs/pipeline (:pipeline workload)))
      (is (util/absent? workload :common)))))

(deftest test-exec-with-input_bam
  (letfn [(go! [workflow]
            (is (:uuid workflow))
            (is (:status workflow))
            (is (:updated workflow)))
          (use-input_bam [item]
            (update item :inputs
              #(-> %
                 (dissoc :input_cram)
                 (assoc :input_bam "gs://inputs/fake.bam"))))
          (verify-use_input_bam! [env in out inputs options labels]
            (is (str/ends-with? in ".bam"))
            (is (contains? inputs :input_bam))
            (is (util/absent? inputs :input_cram))
            (is (contains? labels :workload))
            [env in out inputs options labels])]
    (with-redefs-fn
      {#'wgs/really-submit-one-workflow
       (comp mock-really-submit-one-workflow verify-use_input_bam!)}
      #(-> (make-wgs-workload-request)
         (update :items (comp vector use-input_bam first))
         (workloads/execute-workload!)
         (as-> workload
           (is (:started workload))
           (run! go! (:workflows workload)))))))

(deftest test-workflow-options
  (let [option-sequence [:a :a :b]
        workload-request (-> (make-wgs-workload-request)
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
                         (util/make-options (wgs/get-cromwell-wgs-environment (:cromwell workload)))
                         workload))))))
    (testing "Options sent to Cromwell"
      (is (= (count option-sequence)
             (:c @submitted-option-counts)))
      (is (= (count (filter (partial = :a) option-sequence))
             (:a @submitted-option-counts)))
      (is (= (count (filter (partial = :b) option-sequence))
             (:b @submitted-option-counts))))))

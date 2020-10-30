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
            [wfl.util :as util])
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
  (let [id (old-create-wgs-workload!)]
    (testing "loading a wgs workload saved in a previous release"
      (let [workload (workloads/load-workload-for-id id)]
        (is (= id (:id workload)))
        (is (= wgs/pipeline (:pipeline workload)))))))

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
          (verify-use_input_bam! [env in out inputs labels]
            (is (str/ends-with? in ".bam"))
            (is (contains? inputs :input_bam))
            (is (util/absent? inputs :input_cram))
            (is (contains? labels :workload))
            [env in out inputs labels])]
    (with-redefs-fn
      {#'wgs/really-submit-one-workflow
       (comp mock-really-submit-one-workflow verify-use_input_bam!)}
      #(-> (make-wgs-workload-request)
         (update :items (comp vector use-input_bam first))
         (workloads/execute-workload!)
         (as-> workload
           (is (:started workload))
           (run! go! (:workflows workload)))))))

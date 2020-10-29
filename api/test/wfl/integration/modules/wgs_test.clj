(ns wfl.integration.modules.wgs-test
  (:require [clojure.test :refer [deftest testing is] :as clj-test]
            [wfl.service.cromwell :refer [wait-for-workflow-complete]]
            [wfl.tools.endpoints :as endpoints]
            [wfl.tools.fixtures :as fixtures]
            [wfl.tools.workloads :as workloads]
            [wfl.module.wgs :as wgs]
            [wfl.jdbc :as jdbc]
            [wfl.module.all :as all])
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
      (let [[uuid table] (all/add-workload-table! tx wgs/workflow-wdl request)
            add-id       (fn [m id] (assoc (:inputs m) :id id))]
        (jdbc/insert-multi! tx table (map add-id (:items request) (range)))
        (jdbc/update! tx :workload {:version "0.3.8"} ["uuid = ?" uuid])
        uuid))))

(deftest test-loading-old-wgs-workload
  (let [uuid (old-create-wgs-workload!)]
    (testing "loading a wgs workload saved in a previous release"
      (let [workload (workloads/load-workload-for-uuid uuid)]
        (is (= uuid (:uuid workload)))
        (is (= wgs/pipeline (:pipeline workload)))))))

(ns wfl.integration.wgs-pipeline-test
  (:require [clojure.test :refer [deftest testing is] :as clj-test]
            [wfl.service.cromwell :refer [wait-for-workflow-complete]]
            [wfl.tools.endpoints :as endpoints]
            [wfl.tools.fixtures :as fixtures]
            [wfl.tools.workloads :as workloads]
            [wfl.module.wgs :as wgs]
            [wfl.service.postgres :as postgres]
            [wfl.jdbc :as jdbc])
  (:import (java.util UUID)))

(clj-test/use-fixtures :once fixtures/clean-db-fixture)

(defn- make-wgs-workload-request [id]
  (->
    (workloads/wgs-workload-request id)
    (assoc :creator (:email @endpoints/userinfo))))

(defn create-workload! [workload-request]
  (jdbc/with-db-transaction [tx (postgres/wfl-db-config)]
                            (wfl.api.workloads/create-workload! tx workload-request)))

(defn start-workload! [workload]
  (jdbc/with-db-transaction [tx (postgres/wfl-db-config)]
                            (wfl.api.workloads/start-workload! tx workload)))

(defn- mock-really-submit-one-workflow
  [& _]
  (UUID/randomUUID))

(deftest start-wgs-workload!
  (with-redefs-fn {#'wgs/really-submit-one-workflow mock-really-submit-one-workflow}
    #(let [workload (->>
                      (make-wgs-workload-request (UUID/randomUUID))
                      create-workload!
                      start-workload!)]
       (letfn [(check-nesting [workflow]
                 (do
                   (is (= (:inputs workflow) workloads/wgs-inputs)
                       "Inputs are under :inputs")
                   (is (not-any? (partial contains? workflow) (keys workloads/wgs-inputs))
                       "Inputs are not at the top-level")))]
         (run! check-nesting (:workflows workload))))))
(ns wfl.integration.modules.wgs-test
  (:require [clojure.test :refer [deftest testing is] :as clj-test]
            [wfl.service.cromwell :refer [wait-for-workflow-complete]]
            [wfl.tools.endpoints :as endpoints]
            [wfl.tools.fixtures :as fixtures]
            [wfl.tools.workloads :as workloads]
            [wfl.module.wgs :as wgs])
  (:import (java.util UUID)))

(clj-test/use-fixtures :once fixtures/clean-db-fixture)

(defn- mock-really-submit-one-workflow
  [& _]
  (UUID/randomUUID))

(deftest start-wgs-workload!
  (with-redefs-fn {#'wgs/really-submit-one-workflow mock-really-submit-one-workflow}
    #(let [workload (-> (workloads/wgs-workload-request (UUID/randomUUID))
                      (assoc :creator (:email @endpoints/userinfo))
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

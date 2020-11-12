(ns wfl.integration.workloads-test
  (:require [clojure.test :refer [deftest testing is] :as clj-test]
            [clojure.test :refer :all]
            [wfl.jdbc :as jdbc]
            [wfl.module.all :as all]
            [wfl.module.copyfile :as copyfile]
            [wfl.tools.endpoints :as endpoints]
            [wfl.tools.fixtures :as fixtures]
            [wfl.tools.workloads :as workloads]))

(clj-test/use-fixtures :once fixtures/temporary-postgresql-database)

(defn ^:private make-copyfile-workload-request
  [src dst]
  (-> (workloads/copyfile-workload-request src dst)
      (assoc :creator (:email @endpoints/userinfo))))

(defn ^:private old-create-copyfile-workload! []
  (let [request (make-copyfile-workload-request "gs://fake/input" "gs://fake/output")]
    (jdbc/with-db-transaction [tx (fixtures/testing-db-config)]
      (let [[id table] (all/add-workload-table! tx copyfile/workflow-wdl request)
            add-id (fn [m id] (assoc (:inputs m) :id id))]
        (jdbc/insert-multi! tx table (map add-id (:items request) (range)))
        (jdbc/update! tx :workload {:version "0.3.8"} ["id = ?" id])
        table))))

(deftest test-loading-old-copyfile-workload-with-project
  (let [project (:project (old-create-copyfile-workload!))
        workloads (workloads/load-workloads-with-project project)]
    (is (every? #(= copyfile/pipeline (:pipeline %)) workloads))
    (is (every? #(= project (:project %)) workloads))))

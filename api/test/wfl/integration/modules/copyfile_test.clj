(ns wfl.integration.modules.copyfile-test
  (:require [clojure.test :refer [deftest testing is] :as clj-test]
            [wfl.jdbc :as jdbc]
            [wfl.module.all :as all]
            [wfl.module.copyfile :as copyfile]
            [wfl.service.cromwell :refer [wait-for-workflow-complete submit-workflow]]
            [wfl.tools.endpoints :as endpoints]
            [wfl.tools.fixtures :as fixtures]
            [wfl.tools.workloads :as workloads]
            [wfl.util :as util])
  (:import (java.util UUID)))

(clj-test/use-fixtures :once fixtures/temporary-postgresql-database)

(defn ^:private make-copyfile-workload-request
  [src dst]
  (-> (workloads/copyfile-workload-request src dst)
    (assoc :creator (:email @endpoints/userinfo))))

(defn ^:private old-create-wgs-workload! []
  (let [request (make-copyfile-workload-request "gs://fake/input" "gs://fake/output")]
    (jdbc/with-db-transaction [tx (fixtures/testing-db-config)]
      (let [[id table] (all/add-workload-table! tx copyfile/workflow-wdl request)
            add-id (fn [m id] (assoc (:inputs m) :id id))]
        (jdbc/insert-multi! tx table (map add-id (:items request) (range)))
        (jdbc/update! tx :workload {:version "0.3.8"} ["id = ?" id])
        id))))

(deftest test-loading-old-copyfile-workload
  (let [id       (old-create-wgs-workload!)
        workload (workloads/load-workload-for-id id)]
    (is (= id (:id workload)))
    (is (= copyfile/pipeline (:pipeline workload)))))

(deftest test-workflow-options
  (letfn [(verify-workflow-options [options]
            (is (:supports_common_options options))
            (is (:supports_options options))
            (is (:overwritten options)))
          (verify-submitted-options [env _ _ _ options _]
            (let [defaults (util/make-options env)]
              (verify-workflow-options options)
              (is (= defaults (select-keys options (keys defaults))))
              (UUID/randomUUID)))]
    (with-redefs-fn {#'submit-workflow verify-submitted-options}
      (fn []
        (->
          (make-copyfile-workload-request "gs://fake/input" "gs://fake/output")
          (assoc-in [:common :options] {:supports_common_options true
                                        :overwritten             false})
          (update :items (partial map #(assoc % :options
                                                {:supports_options true
                                                 :overwritten      true})))
          workloads/execute-workload!
          :workflows
          (->> (map (comp verify-workflow-options :options))))))))

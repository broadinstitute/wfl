(ns wfl.integration.modules.copyfile-test
  (:require [clojure.test :refer [deftest testing is] :as clj-test]
            [clojure.string :as str]
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

(defn ^:private old-create-copyfile-workload! []
  (let [request (make-copyfile-workload-request "gs://fake/input" "gs://fake/output")]
    (jdbc/with-db-transaction [tx (fixtures/testing-db-config)]
      (let [[id table] (all/add-workload-table! tx copyfile/workflow-wdl request)
            add-id (fn [m id] (assoc (:inputs m) :id id))]
        (jdbc/insert-multi! tx table (map add-id (:items request) (range)))
        (jdbc/update! tx :workload {:version "0.3.8"} ["id = ?" id])
        id))))

(deftest test-update-unstarted
  (let [workload (->> (make-copyfile-workload-request "gs://fake/input" "gs://fake/output")
                      workloads/create-workload!
                      workloads/update-workload!)]
    (is (nil? (:finished workload)))
    (is (nil? (:submitted workload)))))

(deftest test-loading-old-copyfile-workload
  (let [id       (old-create-copyfile-workload!)
        workload (workloads/load-workload-for-id id)]
    (is (= id (:id workload)))
    (is (= copyfile/pipeline (:pipeline workload)))))

(deftest test-workflow-options
  (letfn [(verify-workflow-options [options]
            (is (:supports_common_options options))
            (is (:supports_options options))
            (is (:overwritten options)))
          (verify-submitted-options [url _ _ options _]
            (let [defaults (copyfile/make-workflow-options url)]
              (verify-workflow-options options)
              (is (= defaults (select-keys options (keys defaults))))
              (UUID/randomUUID)))]
    (with-redefs-fn {#'submit-workflow verify-submitted-options}
      (fn []
        (->
         (make-copyfile-workload-request "gs://fake/input" "gs://fake/output")
         (assoc-in [:common :options]
                   {:supports_common_options true :overwritten false})
         (update :items
                 (partial map
                          #(assoc % :options {:supports_options true :overwritten true})))
         workloads/execute-workload!
         :workflows
         (->> (map (comp verify-workflow-options :options))))))))

(deftest test-submitted-workflow-inputs
  (letfn [(prefixed? [prefix key] (str/starts-with? (str key) (str prefix)))
          (strip-prefix [[k v]]
            [(keyword (util/unprefix (str k) ":copyfile."))
             v])
          (verify-workflow-inputs [inputs]
            (is (:supports_common_inputs inputs))
            (is (:supports_inputs inputs))
            (is (:overwritten inputs)))
          (verify-submitted-inputs [_ _ inputs _ _]
            (is (every? #(prefixed? :copyfile %) (keys inputs)))
            (verify-workflow-inputs (into {} (map strip-prefix inputs)))
            (UUID/randomUUID))]
    (with-redefs-fn {#'submit-workflow verify-submitted-inputs}
      (fn []
        (->
         (make-copyfile-workload-request "gs://fake/foo" "gs://fake/bar")
         (assoc-in [:common :inputs]
                   {:supports_common_inputs true :overwritten false})
         (update :items
                 (partial map
                          #(update % :inputs
                                   (fn [xs] (merge xs {:supports_inputs true :overwritten true})))))
         workloads/execute-workload!)))))

(deftest test-empty-workflow-options
  (letfn [(go! [workflow] (is (util/absent? workflow :options)))]
    (run! go! (-> (make-copyfile-workload-request "in" "out")
                  (assoc-in [:common :options] {})
                  (update :items (partial map #(assoc % :options {})))
                  workloads/create-workload!
                  :workflows))))

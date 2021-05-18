(ns wfl.integration.modules.covid-test
  "Test the Sarscov2IlluminaFull COVID pipeline."
  (:require [clojure.test          :refer :all]
            [clojure.string        :as str]
            [wfl.jdbc              :as jdbc]
            [wfl.module.covid      :as covid]
            [wfl.service.firecloud :as firecloud]
            [wfl.service.postgres  :as postgres]
            [wfl.service.rawls     :as rawls]
            [wfl.tools.fixtures    :as fixtures]
            [wfl.tools.workloads   :as workloads]
            [wfl.tools.resources   :as resources]
            [wfl.util              :as util])
  (:import [java.util ArrayDeque UUID]))

(use-fixtures :once fixtures/temporary-postgresql-database)

(def ^:private testing-dataset "cd25d59e-1451-44d0-8a24-7669edb9a8f8")
(def ^:private testing-workspace "wfl-dev/CDC_Viral_Sequencing")
(def ^:private testing-method-configuration "cdc-covid-surveillance/sarscov2_illumina_full")
(def ^:private testing-table-name "flowcells")
(def ^:private testing-column-name "run_date")

(def workload {:id 1})

;; For temporary workspace creation
(def workspace-prefix "wfl-dev/test-workspace")
(def group "workflow-launcher-dev")

(def snapshot-id "7cb392d8-949b-419d-b40b-d039617d2fc7")
(def reference-id "2d15f9bd-ecb9-46b3-bb6c-f22e20235232")

;; Queue mocks
(def ^:private test-queue-type "TestQueue")
(defn ^:private make-queue-from-list [items]
  {:type test-queue-type :queue (ArrayDeque. items)})

(defn ^:private test-queue-peek [this]
  (-> this :queue .getFirst))

(defn ^:private test-queue-pop [this]
  (-> this :queue .removeFirst))

;; Snapshot reference mock
(def ^:private snapshot-reference-id
  (str (UUID/randomUUID)))
(defn ^:private mock-rawls-create-snapshot-reference [& _]
  {:referenceId snapshot-reference-id})

;; Submission mock
(def ^:private submission-id
  (str (UUID/randomUUID)))
(def ^:private running-workflow
  {:status "Running" :workflowId (str (UUID/randomUUID))})
(def ^:private succeeded-workflow
  {:status "Succeeded" :workflowId (str (UUID/randomUUID))})
(defn ^:private mock-create-submission [& _]
  {:submissionId submission-id
   :workflows [running-workflow succeeded-workflow]})

(let [new-env {"WFL_FIRECLOUD_URL" "https://api.firecloud.org"
               "WFL_TDR_URL"       "https://data.terra.bio"
               "WFL_RAWLS_URL"     "https://rawls.dsde-prod.broadinstitute.org"}]
  (use-fixtures :once
    (fixtures/temporary-environment new-env)
    fixtures/temporary-postgresql-database
    (fixtures/method-overload-fixture
     covid/peek-queue! test-queue-type test-queue-peek)
    (fixtures/method-overload-fixture
     covid/pop-queue! test-queue-type test-queue-pop)))

;; For temporary workspace creation
(def workspace-prefix "wfl-dev/test-workspace")
(def group "workflow-launcher-dev")

(deftest test-create-workload
  (wfl.debug/trace (System/getenv "WFL_TDR_URL"))
  (wfl.debug/trace (wfl.auth/service-account-email))
  (letfn [(verify-source [{:keys [type last_checked details]}]
            (is (= type "TerraDataRepoSource"))
            (is (not last_checked) "The TDR should not have been checked yet")
            (is (str/starts-with? details "TerraDataRepoSourceDetails_")))
          (verify-executor [{:keys [type details]}]
            (is (= type "TerraExecutor"))
            (is (str/starts-with? details "TerraExecutorDetails_")))
          (verify-sink [{:keys [type details]}]
            (is (= type "TerraWorkspaceSink"))
            (is (str/starts-with? details "TerraWorkspaceSinkDetails_")))]
    (let [{:keys [created creator source executor sink labels watchers]}
          (workloads/create-workload!
            (workloads/covid-workload-request {:dataset testing-dataset
                                               :table   testing-table-name
                                               :column  testing-column-name}
                                              {:workspace            testing-workspace
                                               :method_configuration testing-method-configuration}
                                              {:workspace testing-workspace}))]
      (is created "workload is missing :created timestamp")
      (is creator "workload is missing :creator field")
      (is (and source (verify-source source)))
      (is (and executor (verify-executor executor)))
      (is (and sink (verify-sink sink)))
      (is (seq labels) "workload did not contain any labels")
      (is (contains? (set labels) (str "pipeline:" covid/pipeline)))
      (is (vector? watchers)))))

;(defn list-datasets
;  "Query the DataRepo for all the Datasets."
;  []
;  (-> (repository "datasets")
;      (http/get {:content-type :application/json
;                 :headers      (auth/get-service-account-header)
;                 :params       {:limit 999}})
;      util/response-body-json))
;
;(comment
;  (test-vars [#'test-create-workload])
;  (list-datasets)
;  (datarepo-url)
;  (auth/service-account-email)
;  )

(defn ^:private make-covid-workload-request []
  (-> (workloads/covid-workload-request {} {} {})
      (assoc :creator @workloads/email)))

;(deftest test-create-covid-workload
;  (testing "That COVID workload-request can pass verification"
;    (let [workload (workloads/create-workload! (make-covid-workload-request))]
;      (is workload))))

(deftest test-create-covid-workload-with-misnamed-source
    (is (thrown? RuntimeException (-> (make-covid-workload-request)
                                      (assoc-in [:source :name] "Bad_Name")
                                      workloads/create-workload!))))

(deftest test-create-covid-workload-without-source-name
    (is (thrown? RuntimeException (-> (make-covid-workload-request)
                                      (assoc-in [:source :name] nil)
                                      workloads/create-workload!))))

(deftest test-create-covid-workload-without-dataset
    (is (thrown? RuntimeException (-> (make-covid-workload-request)
                                      (assoc-in [:source :dataset] nil)
                                      workloads/create-workload!))))

(deftest test-create-covid-workload-with-misnamed-executor
    (is (thrown? RuntimeException (-> (make-covid-workload-request)
                                      (assoc-in [:executor :name] "Bad_Name")
                                      workloads/create-workload!))))

(deftest test-create-covid-workload-without-named-executor
    (is (thrown? RuntimeException (-> (make-covid-workload-request)
                                      (assoc-in [:executor :name] nil)
                                      workloads/create-workload!))))

(deftest test-create-covid-workload-without-method-configuration
    (is (thrown? RuntimeException (-> (make-covid-workload-request)
                                      (assoc-in [:executor :method_configuration] nil)
                                      workloads/create-workload!))))

(deftest test-create-covid-workload-with-misnamed-sink
    (is (thrown? RuntimeException (-> (make-covid-workload-request)
                                      (assoc-in [:sink :name] "Bad_Name")
                                      workloads/create-workload!))))

(deftest test-create-covid-workload-without-named-sink
    (is (thrown? RuntimeException (-> (make-covid-workload-request)
                                      (assoc-in [:sink :name] nil)
                                      workloads/create-workload!))))

(deftest test-start-workload
  (let [workload (workloads/create-workload!
                  (workloads/covid-workload-request {:dataset testing-dataset
                                                     :table testing-table-name
                                                     :column testing-column-name}
                                                    {:workspace testing-workspace
                                                     :method_configuration testing-method-configuration}
                                                    {:workspace testing-workspace}))]
    (is (not (:started workload)))
    (is (:started (workloads/start-workload! workload)))))

(deftest test-update-terra-executor
  (fixtures/with-temporary-workspace
    workspace-prefix group
    (fn [workspace]
      (let [snapshot {:name "test-snapshot-name"
                      :id   (str (UUID/randomUUID))}
            source (make-queue-from-list [snapshot])
            executor (jdbc/with-db-transaction [tx (postgres/wfl-db-config)]
                       (->> {:name                       "Terra"
                             :workspace                  workspace
                             :methodConfiguration        "mc-namespace/mc-name"
                             :methodConfigurationVersion 1
                             :fromSource                 "importSnapshot"
                             :skipValidation             true}
                            (covid/create-executor! tx 0)
                            (zipmap [:executor_type :executor_items])
                            (covid/load-executor! tx)))
            verify-record-against-workflow
            (fn [record workflow idx]
              (is (= idx (:id record)))
              (is (= (:status workflow) (:workflow_status record)))
              (is (= (:workflowId workflow) (:workflow_id record))))]
        (with-redefs-fn
          {#'rawls/create-snapshot-reference   mock-rawls-create-snapshot-reference
           #'covid/create-submission!          mock-create-submission}
          #(covid/update-executor! source executor))
        (is (-> source :queue empty?) "The snapshot was not consumed.")
        (jdbc/with-db-transaction [tx (postgres/wfl-db-config)]
          (let [[running-record succeeded-record & _ :as records]
                (->> executor :details (postgres/get-table tx))]
            (is (== 2 (count records))
                "The workflows were not written to the database")
            (is (every? #(= snapshot-reference-id (:snapshot_reference_id %)) records))
            (is (every? #(= submission-id (:rawls_submission_id %)) records))
            (is (every? #(nil? (:consumed %)) records))
            (verify-record-against-workflow running-record running-workflow 1)
            (verify-record-against-workflow succeeded-record succeeded-workflow 2)))))))

(deftest test-update-terra-workspace-sink
  (fixtures/with-temporary-workspace
    workspace-prefix group
    (fn [workspace]
      (let [flowcell-id
            "test"
            workflow
            {:uuid    "2768b29e-c808-4bd6-a46b-6c94fd2a67aa"
             :status  "Succeeded"
             :outputs (-> "sarscov2_illumina_full/outputs.edn"
                          resources/read-resource
                          (assoc :flowcell_id flowcell-id))}
            executor
            (make-queue-from-list [workflow])
            sink
            (jdbc/with-db-transaction [tx (postgres/wfl-db-config)]
              (->> {:name           "Terra Workspace"
                    :workspace      workspace
                    :entity         "flowcell"
                    :fromOutputs    (resources/read-resource
                                     "sarscov2_illumina_full/entity-from-outputs.edn")
                    :identifier     "flowcell_id"
                    :skipValidation true}
                   (covid/create-sink! tx 0)
                   (zipmap [:sink_type :sink_items])
                   (covid/load-sink! tx)))]
        (covid/update-sink! executor sink)
        (is (-> executor :queue empty?) "The workflow was not consumed")
        (jdbc/with-db-transaction [tx (postgres/wfl-db-config)]
          (let [records (->> sink :details (postgres/get-table tx))]
            (is (== 1 (count records))
                "The record was not written to the database")
            (is (= (:uuid workflow) (-> records first :workflow))
                "The workflow UUID was not written")))
        (let [[{:keys [name]} & _]
              (util/poll
               (fn [] (seq (firecloud/list-entities workspace "flowcell"))))]
          (is (= name flowcell-id) "The test entity was not created"))))))

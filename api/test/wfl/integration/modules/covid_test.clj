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

(let [new-env {"WFL_FIRECLOUD_URL"
               "https://firecloud-orchestration.dsde-dev.broadinstitute.org"}]
  (use-fixtures :once
    (fixtures/temporary-environment new-env)
    fixtures/temporary-postgresql-database
    (fixtures/method-overload-fixture
     covid/peek-queue! test-queue-type test-queue-peek)
    (fixtures/method-overload-fixture
     covid/pop-queue! test-queue-type test-queue-pop)))

;; For temporary workspace creation
(def workspace-prefix "general-dev-billing-account/test-workspace")
(def group "hornet-eng")

(deftest test-create-workload
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
           (workloads/covid-workload-request {} {} {}))]
      (is created "workload is missing :created timestamp")
      (is creator "workload is missing :creator field")
      (is (and source (verify-source source)))
      (is (and executor (verify-executor executor)))
      (is (and sink (verify-sink sink)))
      (is (seq labels) "workload did not contain any labels")
      (is (contains? (set labels) (str "pipeline:" covid/pipeline)))
      (is (vector? watchers)))))

(deftest test-start-workload
  (let [workload (workloads/create-workload!
                  (workloads/covid-workload-request {} {} {}))]
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

(deftest test-get-workflows-empty
  (let [workload (workloads/create-workload!
                  (workloads/covid-workload-request {} {} {}))]
    (is (empty? (workloads/workflows workload)))))

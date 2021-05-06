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
  (:import [clojure.lang ExceptionInfo]
           [java.util ArrayDeque]))

;; queue mocks
(def ^:private test-queue-type "TestQueue")
(defn ^:private make-queue-from-list [items]
  {:type test-queue-type :queue (ArrayDeque. items)})

(defn ^:private test-queue-peek [this]
  (-> this :queue .getFirst))

(defn ^:private test-queue-pop [this]
  (-> this :queue .removeLast))

(let [new-env {"WFL_FIRECLOUD_URL"
               "https://firecloud-orchestration.dsde-dev.broadinstitute.org"}]
  (use-fixtures :once
    (fixtures/temporary-environment new-env)
    fixtures/temporary-postgresql-database
    (fixtures/method-overload-fixture
     covid/peek-queue! test-queue-type test-queue-peek)
    (fixtures/method-overload-fixture
     covid/pop-queue! test-queue-type test-queue-pop)))

(def workload {:id 1})

;; For temporary workspace creation
(def workspace-prefix "general-dev-billing-account/test-workspace")
(def group "hornet-eng")

(def snapshot-id "7cb392d8-949b-419d-b40b-d039617d2fc7")
(def reference-id "2d15f9bd-ecb9-46b3-bb6c-f22e20235232")

;; Source details
(def source-details {:id 1 :snapshot_id snapshot-id})

;; Executor and its details
(def executor-base {:details (format "%s_%09d" "TerraExecutorDetails" 1)})
(def ed-base {:id 1})
(def ed-reference (assoc ed-base :snapshot_reference_id reference-id))

(defn ^:private mock-rawls-snapshot-reference [& _]
  {:cloningInstructions "COPY_NOTHING",
   :description "test importing a snapshot into a workspace",
   :name "snapshot",
   :reference {:instanceName "terra", :snapshot snapshot-id},
   :referenceId reference-id,
   :referenceType "DATA_REPO_SNAPSHOT",
   :workspaceId "e9d053b9-d79f-40b7-b701-904bf542ec2d"})

(defn ^:private mock-throw [& _] (throw (ex-info "mocked throw" {})))

#_(deftest test-get-imported-snapshot-reference
    (fixtures/with-temporary-workspace workspace-prefix group
      (fn [workspace]
        (let [executor (assoc executor-base :workspace workspace)
              fetch (fn [ed] (#'covid/get-imported-snapshot-reference executor ed))]
          (with-redefs-fn {#'rawls/get-snapshot-reference mock-throw}
            #(let [go (fn [ed] (is (not (fetch ed))))
                   executor-details [ed-base ed-reference]]
               (run! go executor-details)))
          (with-redefs-fn {#'rawls/get-snapshot-reference mock-rawls-snapshot-reference}
            #(is (fetch ed-reference)))))))

#_(deftest test-import-snapshot
    (fixtures/with-temporary-workspace workspace-prefix group
      (fn [workspace]
        (let [executor (assoc executor-base :workspace workspace)]
          #_(testing "Successful create writes to db"
              (jdbc/with-db-transaction [tx (fixtures/testing-db-config)]
                (with-redefs-fn {#'rawls/create-snapshot-reference mock-rawls-snapshot-reference}
                  #(#'covid/import-snapshot! tx workload source-details executor ed-base))))
          (testing "Failed create throws"
            (jdbc/with-db-transaction [tx (fixtures/testing-db-config)]
              (with-redefs-fn {#'rawls/create-snapshot-reference mock-throw}
                #(is (thrown-with-msg?
                      ExceptionInfo #"mocked throw"
                      (#'covid/import-snapshot! tx workload source-details executor ed-base))))))))))

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

(test-vars [#'test-update-terra-workspace-sink])

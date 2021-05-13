(ns wfl.integration.modules.covid-test
  "Test the Sarscov2IlluminaFull COVID pipeline."
  (:require [clojure.test :refer :all]
            [clojure.string :as str]
            [wfl.debug :as debug]
            [wfl.jdbc :as jdbc]
            [wfl.module.covid :as covid]
            [wfl.service.rawls :as rawls]
            [wfl.tools.fixtures :as fixtures]
            [wfl.tools.workloads :as workloads])
  (:import [clojure.lang ExceptionInfo]
           [java.time OffsetDateTime]
           [java.util UUID]))

(use-fixtures :once fixtures/temporary-postgresql-database)

(def ^:private testing-dataset "cd25d59e-1451-44d0-8a24-7669edb9a8f8")
(def ^:private testing-workspace "wfl-dev/CDC_Viral_Sequencing_GPc586b76e8ef24a97b354cf0226dfe583")
(def ^:private testing-method-configuration "cdc-covid-surveillance/sarscov2_illumina_full")
(def ^:private testing-table-name "flowcells")
(def ^:private testing-column-name "run_date")

(let [new-env {"WFL_FIRECLOUD_URL" "https://api.firecloud.org"
               "WFL_TDR_URL"       "https://data.terra.bio"
               "WFL_RAWLS_URL"     "https://rawls.dsde-prod.broadinstitute.org"}]
  (use-fixtures :once (fixtures/temporary-environment new-env)
    fixtures/temporary-postgresql-database))

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

(deftest test-get-imported-snapshot-reference
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

(deftest test-import-snapshot
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
           (workloads/covid-workload-request {:dataset testing-dataset
                                              :table testing-table-name
                                              :column testing-column-name}
                                             {:workspace testing-workspace
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

(defn ^:private make-covid-workload-request []
  (-> (workloads/covid-workload-request {} {} {})
      (assoc :creator @workloads/email)))

;(deftest test-create-covid-workload
;  (testing "That COVID workload-request can pass verification"
;    (let [workload (workloads/create-workload! (make-covid-workload-request))]
;      (is workload))))

(deftest test-create-covid-workload-with-misnamed-source
  (testing "That a COVID workload-request with a misnamed source cannot pass verification"
    (is (thrown? RuntimeException (-> (make-covid-workload-request)
                                      (assoc-in [:source :name] "Bad_Name")
                                      workloads/create-workload!)))))

(deftest test-create-covid-workload-without-source-name
  (testing "That a COVID workload-request without a named source cannot pass verification"
    (is (thrown? RuntimeException (-> (make-covid-workload-request)
                                      (assoc-in [:source :name] nil)
                                      workloads/create-workload!)))))

(deftest test-create-covid-workload-without-dataset
  (testing "That a COVID workload-request without a dataset cannot pass verification"
    (is (thrown? RuntimeException (-> (make-covid-workload-request)
                                      (assoc-in [:source :dataset] nil)
                                      workloads/create-workload!)))))

(deftest test-create-covid-workload-with-misnamed-executor
  (testing "That a COVID workload-request with a misnamed executor cannot pass verification"
    (is (thrown? RuntimeException (-> (make-covid-workload-request)
                                      (assoc-in [:executor :name] "Bad_Name")
                                      workloads/create-workload!)))))

(deftest test-create-covid-workload-without-named-executor
  (testing "That a COVID workload-request without a named executor cannot pass verification"
    (is (thrown? RuntimeException (-> (make-covid-workload-request)
                                      (assoc-in [:executor :name] nil)
                                      workloads/create-workload!)))))

(deftest test-create-covid-workload-without-method-configuration
  (testing "That a COVID workload-request without a method_configuration cannot pass verification"
    (is (thrown? RuntimeException (-> (make-covid-workload-request)
                                      (assoc-in [:executor :method_configuration] nil)
                                      workloads/create-workload!)))))

(deftest test-create-covid-workload-with-misnamed-sink
  (testing "That a COVID workload-request with a misnamed sink cannot pass verification"
    (is (thrown? RuntimeException (-> (make-covid-workload-request)
                                      (assoc-in [:sink :name] "Bad_Name")
                                      workloads/create-workload!)))))

(deftest test-create-covid-workload-without-named-sink
  (testing "That a COVID workload-request without a named sink cannot pass verification"
    (is (thrown? RuntimeException (-> (make-covid-workload-request)
                                      (assoc-in [:sink :name] nil)
                                      workloads/create-workload!)))))

(deftest test-start-workload
  (let [workload (workloads/create-workload!
                  (workloads/covid-workload-request {} {} {}))]
    (is (not (:started workload)))
    (is (:started (workloads/start-workload! workload)))))

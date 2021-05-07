"Test the Sarscov2IlluminaFull COVID pipeline."
(ns wfl.integration.modules.covid-test
  (:require [clojure.test :refer :all]
            [clojure.test :as clj-test]
            [wfl.jdbc :as jdbc]
            [wfl.module.covid :as covid]
            [wfl.service.rawls :as rawls]
            [wfl.tools.fixtures :as fixtures]
            [wfl.tools.workloads :as workloads]))

(:import [clojure.lang ExceptionInfo])

(clj-test/use-fixtures :once fixtures/temporary-postgresql-database)

(def ^:private testing-dataset "ff6e2b40-6497-4340-8947-2f52a658f561")
(def ^:private testing-workspace "wfl-dev/CDC_Viral_Sequencing")
(def ^:private testing-method-configuration "pathogen-genomic-surveillance/sarscov2_illumina_full")

(defn ^:private make-covid-workload-request []
  (-> (workloads/covid-workload-request testing-dataset
                                        testing-method-configuration
                                        testing-workspace)
      (assoc :creator @workloads/email)))

(deftest test-create-covid-workload
  (testing "That COVID workload-request can pass verification"
    (let [workload (workloads/create-workload! (make-covid-workload-request))]
      (is workload))))

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

(deftest test-create-covid-workload-without-workspace
  (testing "That a COVID workload-request without a workspace cannot pass verification"
    (is (thrown? RuntimeException (-> (make-covid-workload-request)
                                      (assoc-in [:sink :workspace] nil)
                                      workloads/create-workload!)))))

(let [new-env {"WFL_FIRECLOUD_URL"
               "https://firecloud-orchestration.dsde-dev.broadinstitute.org"}]
  (clj-test/use-fixtures :once (fixtures/temporary-environment new-env)
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

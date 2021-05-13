(ns wfl.integration.modules.covid-test
  "Test the Sarscov2IlluminaFull COVID pipeline."
  (:require [clojure.test         :refer :all]
            [clojure.string       :as str]
            [wfl.jdbc             :as jdbc]
            [wfl.module.covid     :as covid]
            [wfl.service.postgres :as postgres]
            [wfl.service.rawls    :as rawls]
            [wfl.tools.fixtures   :as fixtures]
            [wfl.tools.workloads  :as workloads])
  (:import [clojure.lang ExceptionInfo]))

(let [new-env {"WFL_FIRECLOUD_URL"
               "https://firecloud-orchestration.dsde-dev.broadinstitute.org"}]
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
            (jdbc/with-db-transaction [tx (postgres/wfl-db-config)]
              (with-redefs-fn {#'rawls/create-snapshot-reference mock-rawls-snapshot-reference}
                #(#'covid/import-snapshot! tx workload source-details executor ed-base))))
        (testing "Failed create throws"
          (jdbc/with-db-transaction [tx (postgres/wfl-db-config)]
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

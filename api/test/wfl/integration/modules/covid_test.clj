(ns wfl.integration.modules.covid-test
  "Test the Sarscov2IlluminaFull COVID pipeline."
  (:require [clojure.test                   :refer [deftest is testing
                                                    use-fixtures]]
            [clojure.set                    :as set]
            [clojure.spec.alpha             :as s]
            [clojure.string                 :as str]
            [reitit.coercion.spec]
            [reitit.ring                    :as ring]
            [reitit.ring.coercion           :as coercion]
            [wfl.api.spec                   :as spec]
            [wfl.integration.modules.shared :as shared]
            [wfl.service.firecloud          :as firecloud]
            [wfl.service.rawls              :as rawls]
            [wfl.stage                      :as stage]
            [wfl.source                     :as source]
            [wfl.tools.fixtures             :as fixtures]
            [wfl.tools.workloads            :as workloads]
            [wfl.util                       :as util])
  (:import [java.time LocalDateTime]
           [java.util UUID]
           [wfl.util  UserException]))

;; Snapshot creation mock
(def ^:private mock-new-rows-size 2021)
(defn ^:private mock-find-new-rows [_ interval]
  (is (every? #(LocalDateTime/parse % @#'source/bigquery-datetime-format) interval))
  (take mock-new-rows-size (range)))
(defn ^:private mock-create-snapshots [_ _ row-ids]
  (letfn [(f [idx shard] [(vec shard) (format "mock_job_id_%s" idx)])]
    (->> (partition-all 500 row-ids)
         (map-indexed f))))

;; Note this mock only covers happy paths of TDR jobs
(defn ^:private mock-check-tdr-job [job-id]
  {:snapshot_id (str (UUID/randomUUID))
   :job_status "succeeded"
   :id job-id})

(def ^:private testing-dataset "cd25d59e-1451-44d0-8a24-7669edb9a8f8")
(def ^:private testing-snapshot "e8f1675e-1e7c-48b4-92ab-3598425c149d")
(def ^:private testing-namespace "wfl-dev")
(def ^:private testing-workspace (str testing-namespace "/" "CDC_Viral_Sequencing"))
(def ^:private testing-method-name "sarscov2_illumina_full")
(def ^:private testing-method-configuration (str testing-namespace "/" testing-method-name))
(def ^:private testing-method-configuration-version 1)
(def ^:private testing-table-name "flowcells")
(def ^:private testing-column-name "run_date")

;; Queue mocks
(def ^:private testing-queue-type "TestQueue")

(defn ^:private testing-queue-peek [this]
  (-> this :queue .getFirst))

(defn ^:private testing-queue-pop [this]
  (-> this :queue .removeFirst))

(defn ^:private testing-queue-length [this]
  (-> this :queue .size))

(defn ^:private testing-queue-done? [this]
  (-> this :queue .empty))

(let [new-env {"WFL_FIRECLOUD_URL" "https://api.firecloud.org"
               "WFL_TDR_URL"       "https://data.terra.bio"
               "WFL_RAWLS_URL"     "https://rawls.dsde-prod.broadinstitute.org"}]

  (use-fixtures :once
    (fixtures/temporary-environment new-env)
    fixtures/temporary-postgresql-database
    (fixtures/method-overload-fixture
     stage/peek-queue testing-queue-type testing-queue-peek)
    (fixtures/method-overload-fixture
     stage/pop-queue! testing-queue-type testing-queue-pop)
    (fixtures/method-overload-fixture
     stage/queue-length testing-queue-type testing-queue-length)
    (fixtures/method-overload-fixture
     stage/done? testing-queue-type testing-queue-done?)))

(deftest test-create-workload
  (letfn [(verify-source [{:keys [type last_checked details]}]
            (is (= type "TerraDataRepoSource"))
            (is (not last_checked) "The TDR should not have been checked yet")
            (is (str/starts-with? details "TerraDataRepoSource_")))
          (verify-executor [{:keys [type details]}]
            (is (= type "TerraExecutor"))
            (is (str/starts-with? details "TerraExecutor_")))
          (verify-sink [{:keys [type details]}]
            (is (= type "TerraWorkspaceSink"))
            (is (str/starts-with? details "TerraWorkspaceSink_")))]
    (let [{:keys [created creator source executor sink labels watchers] :as workload}
          (workloads/create-workload!
           (workloads/covid-workload-request
            {:skipValidation true}
            {:skipValidation true}
            {:skipValidation true}))]
      (is created "workload is missing :created timestamp")
      (is creator "workload is missing :creator field")
      (is (and source (verify-source source)))
      (is (and executor (verify-executor executor)))
      (is (and sink (verify-sink sink)))
      (is (seq labels) "workload did not contain any labels")
      (is (contains? (set labels) (str "project:" @workloads/project)))
      (is (util/absent? workload :pipeline) ":pipeline should not be defined")
      (is (vector? watchers)))))

(deftest test-workload-to-edn
  (let [request  (workloads/covid-workload-request
                  {:skipValidation true}
                  {:skipValidation true}
                  {:skipValidation true})]
    (is (s/valid? :wfl.api.spec/workload-request request)
        (s/explain-str :wfl.api.spec/workload-request request))
    (let [workload (util/to-edn (workloads/create-workload! request))]
      (is (not-any? workload [:id
                              :items
                              :source_type
                              :source_items
                              :executor_type
                              :executor_items
                              :sink_type
                              :sink_items
                              :type]))
      (is (not-any? (:source workload) [:id :details :type :last_checked]))
      (is (not-any? (:executor workload) [:id :details :type]))
      (is (not-any? (:sink workload) [:id :details :type])))))

(deftest test-create-covid-workload-with-misnamed-source
  (is (thrown-with-msg?
       UserException #"Invalid request"
       (workloads/create-workload!
        (workloads/covid-workload-request
         {:name "bad name"}
         {:skipValidation true}
         {:skipValidation true})))))

(deftest test-create-covid-workload-with-valid-executor-request
  (is (workloads/create-workload!
       (workloads/covid-workload-request
        {:skipValidation true}
        {:workspace                  testing-workspace
         :methodConfiguration        testing-method-configuration
         :methodConfigurationVersion testing-method-configuration-version
         :fromSource                 "importSnapshot"}
        {:skipValidation true}))))

(deftest test-create-covid-workload-with-misnamed-executor
  (is (thrown-with-msg?
       UserException #"Invalid request"
       (workloads/create-workload!
        (workloads/covid-workload-request
         {:skipValidation true}
         {:name "bad name"}
         {:skipValidation true})))))

(deftest test-start-workload
  (let [workload (workloads/create-workload!
                  (workloads/covid-workload-request
                   {:skipValidation true}
                   {:skipValidation true}
                   {:skipValidation true}))]
    (is (not (:started workload)))
    (is (:started (workloads/start-workload! workload)))))

;; Mocks
(def ^:private fake-method-name "method-name")
;; Snapshot and snapshot reference mocks
(def ^:private snapshot
  {:name "test-snapshot-name" :id (str (UUID/randomUUID))})

(def ^:private snapshot-reference-id (str (UUID/randomUUID)))
(def ^:private snapshot-reference-name (str (:name snapshot) "-ref"))
(defn ^:private mock-rawls-snapshot-reference [& _]
  {:referenceId snapshot-reference-id
   :name        snapshot-reference-name})

(def ^:private method-config-version-mock 1)

(defn ^:private mock-firecloud-get-method-configuration [& _]
  {:methodConfigVersion method-config-version-mock})

(defn ^:private mock-firecloud-update-method-configuration
  [_ _ {:keys [dataReferenceName methodConfigVersion]}]
  (is (= dataReferenceName snapshot-reference-name)
      "Snapshot reference name should be passed to method config update")
  (is (= methodConfigVersion (inc method-config-version-mock))
      "Incremented version should be passed to method config update")
  nil)

(def ^:private submission-id-mock (str (UUID/randomUUID)))

(def ^:private running-workflow-mock
  {:entityName   "entity"
   :id           (str (UUID/randomUUID))
   :inputs       {:input "value"}
   :status       "Running"
   :workflowName fake-method-name})

(def ^:private succeeded-workflow-mock
  {:entityName   "entity"
   :id           (str (UUID/randomUUID))
   :inputs       {:input "value"}
   :status       "Succeeded"
   :workflowName fake-method-name})

;; when we create submissions, workflows have been queued for execution
(defn ^:private mock-firecloud-create-submission [& _]
  (let [enqueue #(-> % (dissoc :id) (assoc :status "Queued"))]
    {:submissionId submission-id-mock
     :workflows    (map enqueue [running-workflow-mock
                                 succeeded-workflow-mock])}))

;; when we get the submission later, the workflows may have a uuid assigned
(defn ^:private mock-firecloud-get-submission [& _]
  (letfn [(add-workflow-entity [{:keys [entityName] :as workflow}]
            (-> workflow
                (set/rename-keys {:id :workflowId})
                (assoc :workflowEntity {:entityType "test" :entityName entityName})
                (dissoc :entityName)))]
    {:submissionId submission-id-mock
     :workflows    (map add-workflow-entity [running-workflow-mock
                                             succeeded-workflow-mock])}))

;; Workflow fetch mocks within update-workflow-statuses!

(defn ^:private mock-workflow-keep-status [_ _ workflow-id]
  (is (not (= (:workflowId succeeded-workflow-mock) workflow-id))
      "Successful workflow records should be filtered out before firecloud fetch")
  running-workflow-mock)

(deftest test-get-workflows-empty
  (let [workload (workloads/create-workload!
                  (workloads/covid-workload-request
                   {:skipValidation true}
                   {:skipValidation true}
                   {:skipValidation true}))]
    (is (empty? (workloads/workflows workload)))))

(deftest test-workload-state-transition
  (with-redefs-fn
    {#'source/find-new-rows                   mock-find-new-rows
     #'source/create-snapshots                mock-create-snapshots
     #'source/check-tdr-job                   mock-check-tdr-job
     #'rawls/create-or-get-snapshot-reference mock-rawls-snapshot-reference
     #'firecloud/method-configuration         mock-firecloud-get-method-configuration
     #'firecloud/update-method-configuration  mock-firecloud-update-method-configuration
     #'firecloud/submit-method                mock-firecloud-create-submission
     #'firecloud/get-submission               mock-firecloud-get-submission
     #'firecloud/get-workflow                 mock-workflow-keep-status}
    #(shared/run-workload-state-transition-test!
      (workloads/covid-workload-request
       {:skipValidation true}
       {:skipValidation true}
       {:skipValidation true}))))

(deftest test-batch-workload-state-transition
  (shared/run-workload-state-transition-test!
   (workloads/covid-workload-request
    {:name      "TDR Snapshots"
     :snapshots []}
    {:skipValidation true}
    {:skipValidation true})))

(deftest test-stop-workload-state-transition
  (shared/run-stop-workload-state-transition-test!
   (workloads/covid-workload-request
    {:skipValidation true}
    {:skipValidation true}
    {:skipValidation true})))

(deftest test-workload-state-transition-with-failed-workflow
  (with-redefs-fn
    {#'source/find-new-rows                   mock-find-new-rows
     #'source/create-snapshots                mock-create-snapshots
     #'source/check-tdr-job                   mock-check-tdr-job
     #'rawls/create-or-get-snapshot-reference mock-rawls-snapshot-reference
     #'firecloud/method-configuration         mock-firecloud-get-method-configuration
     #'firecloud/update-method-configuration  mock-firecloud-update-method-configuration
     #'firecloud/submit-method                mock-firecloud-create-submission
     #'firecloud/get-workflow                 (constantly {:status "Failed"})}
    #(shared/run-workload-state-transition-test!
      (workloads/covid-workload-request
       {:skipValidation true}
       {:skipValidation true}
       {:skipValidation true}))))

(defn ^:private create-app [input-spec output-spec handler]
  (let [app
        (ring/ring-handler
         (ring/router
          [["/test" {:post {:parameters {:body input-spec}
                            :responses  {200 {:body output-spec}}
                            :handler    (fn [{:keys [body-params]}]
                                          {:status 200
                                           :body   (handler body-params)})}}]]
          {:data {:coercion   reitit.coercion.spec/coercion
                  :middleware [coercion/coerce-exceptions-middleware
                               coercion/coerce-request-middleware
                               coercion/coerce-response-middleware]}}))]
    (fn [request]
      (app {:request-method :post
            :uri            "/test"
            :body-params    request}))))

(deftest test-create-workload-coercion
  (let [app     (create-app ::spec/workload-request
                            ::spec/workload-response
                            (comp util/to-edn workloads/create-workload!))
        request (workloads/covid-workload-request
                 {}
                 {:skipValidation true}
                 {:skipValidation true})]
    (testing "Workload with a TDR Source"
      (let [{:keys [status body]}
            (->>  {:name            "Terra DataRepo"
                   :dataset         testing-dataset
                   :table           testing-table-name
                   :column          testing-column-name
                   :snapshotReaders ["workflow-launcher-dev@firecloud.org"]}
                  (assoc request :source)
                  app)]
        (is (== 200 status) (pr-str body))))
    (testing "Workload with a TDR Snapshots Source"
      (let [{:keys [status body]}
            (->> {:name      "TDR Snapshots"
                  :snapshots [testing-snapshot]}
                 (assoc request :source)
                 app)]
        (is (== 200 status) (pr-str body))))))

(deftest test-retry-workload-throws-when-not-started
  (with-redefs-fn
    {#'source/find-new-rows                   mock-find-new-rows
     #'source/create-snapshots                mock-create-snapshots
     #'source/check-tdr-job                   mock-check-tdr-job
     #'rawls/create-or-get-snapshot-reference mock-rawls-snapshot-reference
     #'firecloud/method-configuration         mock-firecloud-get-method-configuration
     #'firecloud/update-method-configuration  mock-firecloud-update-method-configuration
     #'firecloud/submit-method                mock-firecloud-create-submission
     #'firecloud/get-workflow                 (constantly {:status "Failed"})}
    #(let [workload-request (workloads/covid-workload-request
                             {:skipValidation true}
                             {:skipValidation true}
                             {:skipValidation true})
           workload         (workloads/create-workload! workload-request)]
       (is (not (:started workload)))
       (is (thrown-with-msg?
             UserException #"Cannot retry workload before it's been started."
             (workloads/retry workload []))))))

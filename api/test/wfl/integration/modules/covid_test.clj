(ns wfl.integration.modules.covid-test
  "Test the Sarscov2IlluminaFull COVID pipeline."
  (:require [clojure.test                   :refer :all]
            [clojure.spec.alpha             :as s]
            [clojure.string                 :as str]
            [wfl.api.spec                   :as spec]
            [wfl.integration.modules.shared :as shared]
            [wfl.jdbc                       :as jdbc]
            [wfl.module.covid               :as covid]
            [wfl.service.firecloud          :as firecloud]
            [wfl.service.postgres           :as postgres]
            [wfl.service.rawls              :as rawls]
            [wfl.tools.fixtures             :as fixtures]
            [wfl.tools.workloads            :as workloads]
            [wfl.tools.resources            :as resources]
            [wfl.util                       :as util])
  (:import [java.util ArrayDeque UUID]
           [java.lang Math]
           [wfl.util UserException]))

;; Snapshot creation mock
(def ^:private mock-new-rows-size 2021)
(defn ^:private mock-find-new-rows [_ _] (take mock-new-rows-size (range)))
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
(def ^:private testing-workspace "wfl-dev/CDC_Viral_Sequencing")
(def ^:private testing-method-configuration "cdc-covid-surveillance/sarscov2_illumina_full")
(def ^:private testing-method-configuration-version 2)
(def ^:private testing-table-name "flowcells")
(def ^:private testing-column-name "run_date")

;; Queue mocks
(def ^:private testing-queue-type "TestQueue")
(defn ^:private make-queue-from-list [items]
  {:type testing-queue-type :queue (ArrayDeque. items)})

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
     covid/peek-queue! testing-queue-type testing-queue-peek)
    (fixtures/method-overload-fixture
     covid/pop-queue! testing-queue-type testing-queue-pop)
    (fixtures/method-overload-fixture
     covid/queue-length! testing-queue-type testing-queue-length)
    (fixtures/method-overload-fixture
     covid/done? testing-queue-type testing-queue-done?)))

;; Snapshot and snapshot reference mocks
(def ^:private snapshot
  {:name "test-snapshot-name"
   :id   (str (UUID/randomUUID))})
(def ^:private snapshot-reference-id
  (str (UUID/randomUUID)))
(def ^:private snapshot-reference-name
  (str (:name snapshot) "-ref"))
(defn ^:private mock-rawls-create-snapshot-reference [& _]
  {:referenceId snapshot-reference-id
   :name        snapshot-reference-name})

;; Method configuration mocks
(def methodConfigVersion 1)
(defn ^:private mock-firecloud-get-method-configuration [& _]
  {:methodConfigVersion methodConfigVersion})
(defn ^:private mock-firecloud-update-method-configuration
  [_ _ {:keys [dataReferenceName] :as mc}]
  (is (= dataReferenceName snapshot-reference-name)
      "Snapshot reference name should be passed to method config update")
  (is (= (:methodConfigVersion mc) (inc methodConfigVersion))
      "Incremented version should be passed to method config update")
  nil)

;; Submission mock
(def ^:private submission-id
  (str (UUID/randomUUID)))

(def ^:private running-workflow
  {:status         "Running"
   :workflowId     (str (UUID/randomUUID))
   :workflowEntity {:entityType "foo" :entityName "running"}})

(def ^:private succeeded-workflow
  {:status         "Succeeded"
   :workflowId     (str (UUID/randomUUID))
   :workflowEntity {:entityType "foo" :entityName "running"}})

;; when we create submissions, workflows have been queued for execution
(defn ^:private mock-firecloud-create-submission [& _]
  (let [enqueue #(-> % (dissoc :workflowId) (assoc :staus "Queued"))]
    {:submissionId submission-id
     :workflows    [(enqueue running-workflow) (enqueue succeeded-workflow)]}))

;; when we get the submission later, the workflows may have a uuid assigned
(defn ^:private mock-firecloud-get-submission [& _]
  {:submissionId submission-id
   :workflows    [running-workflow succeeded-workflow]})

(defn ^:private mock-firecloud-create-failed-submission [& _]
  {:submissionId submission-id
   :workflows    [{:status         "Failed"
                   :uuid           (str (UUID/randomUUID))
                   :workflowEntity {:entityType "foo" :entityName "failed"}}
                  {:status         "Aborted"
                   :uuid           (str (UUID/randomUUID))
                   :workflowEntity {:entityType "foo" :entityName "aborted"}}]})

;; Workflow fetch mocks within update-workflow-statuses!
(defn ^:private mock-workflow-update-status [_ _ workflow-id]
  (is (not (= (:workflowId succeeded-workflow) workflow-id))
      "Successful workflow records should be filtered out before firecloud fetch")
  {:status "Succeeded" :workflowId workflow-id})
(defn ^:private mock-workflow-keep-status [_ _ workflow-id]
  (is (not (= (:workflowId succeeded-workflow) workflow-id))
      "Successful workflow records should be filtered out before firecloud fetch")
  running-workflow)

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
    (let [{:keys [created creator source executor sink labels watchers]}
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
      (is (contains? (set labels) (str "pipeline:" covid/pipeline)))
      (is (vector? watchers)))))

(deftest test-workload-to-edn
  (let [workload (util/to-edn
                  (workloads/create-workload!
                   (workloads/covid-workload-request
                    {:skipValidation true}
                    {:skipValidation true}
                    {:skipValidation true})))]
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
    (is (not-any? (:sink workload) [:id :details :type]))))

(deftest test-create-covid-workload-with-misnamed-source
  (is (thrown-with-msg?
       UserException #"Invalid request"
       (workloads/create-workload!
        (workloads/covid-workload-request
         {:name "bad name"}
         {:skipValidation true}
         {:skipValidation true})))))

(deftest test-create-covid-workload-with-valid-source-request
  (is (workloads/create-workload!
       (workloads/covid-workload-request
        {:dataset testing-dataset
         :table   testing-table-name
         :column  testing-column-name}
        {:skipValidation true}
        {:skipValidation true}))))

(deftest test-create-covid-workload-with-non-existent-dataset
  (is (thrown-with-msg?
       UserException #"Cannot access dataset"
       (workloads/create-workload!
        (workloads/covid-workload-request
         {:dataset util/uuid-nil}
         {:skipValidation true}
         {:skipValidation true})))))

(deftest test-create-covid-workload-with-invalid-dataset-table
  (is (thrown-with-msg?
       UserException #"Table not found"
       (workloads/create-workload!
        (workloads/covid-workload-request
         {:dataset testing-dataset
          :table   "no_such_table"}
         {:skipValidation true}
         {:skipValidation true})))))

(deftest test-create-covid-workload-with-invalid-dataset-column
  (is (thrown-with-msg?
       UserException #"Column not found"
       (workloads/create-workload!
        (workloads/covid-workload-request
         {:dataset testing-dataset
          :table   testing-table-name
          :column  "no_such_column"}
         {:skipValidation true}
         {:skipValidation true})))))

(deftest test-create-covid-workload-with-empty-snapshot-list
  (is (workloads/create-workload!
       (workloads/covid-workload-request
        {:name      "TDR Snapshots"
         :snapshots [testing-snapshot]}
        {:skipValidation true}
        {:skipValidation true}))))

(deftest test-create-covid-workload-with-invalid-snapshot
  (is (thrown-with-msg?
       UserException #"Cannot access snapshot"
       (workloads/create-workload!
        (workloads/covid-workload-request
         {:name     "TDR Snapshots"
          :snapshots [util/uuid-nil]}
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

(deftest test-create-covid-workload-with-valid-executor-request
  (is (thrown-with-msg?
       UserException #"Unsupported coercion"
       (workloads/create-workload!
        (workloads/covid-workload-request
         {:skipValidation true}
         {:workspace                  testing-workspace
          :methodConfiguration        testing-method-configuration
          :methodConfigurationVersion testing-method-configuration-version
          :fromSource                 "frobnicate"}
         {:skipValidation true})))))

(deftest test-create-covid-workload-with-wrong-method-configuration
  (is (thrown-with-msg?
       UserException #"Method configuration version mismatch"
       (workloads/create-workload!
        (workloads/covid-workload-request
         {:skipValidation true}
         {:workspace                  testing-workspace
          :methodConfiguration        testing-method-configuration
          :methodConfigurationVersion -1
          :fromSource                 "importSnapshot"}
         {:skipValidation true})))))

(deftest test-create-covid-workload-with-wrong-method-configuration
  (is (thrown-with-msg?
       UserException #"Cannot access method configuration"
       (workloads/create-workload!
        (workloads/covid-workload-request
         {:skipValidation true}
         {:workspace                  testing-workspace
          :methodConfiguration        "no_such/method_configuration"
          :fromSource                 "importSnapshot"}
         {:skipValidation true})))))

(deftest test-create-covid-workload-with-valid-sink-request
  (is (workloads/create-workload!
       (workloads/covid-workload-request
        {:skipValidation true}
        {:skipValidation true}
        {:workspace   testing-workspace
         :entity      "reads"
         :identity    "reads_id"
         :fromOutputs {:submission_xml "submission_xml"}}))))

(deftest test-create-covid-workload-with-invalid-sink-entity-type
  (is (thrown-with-msg?
       UserException #"Entity not found"
       (workloads/create-workload!
        (workloads/covid-workload-request
         {:skipValidation true}
         {:skipValidation true}
         {:workspace   testing-workspace
          :entity      "moo"
          :identity    "reads_id"
          :fromOutputs {:submission_xml "submission_xml"}})))))

(deftest test-create-covid-workload-with-invalid-sink-workspace
  (is (thrown-with-msg?
       UserException #"Cannot access workspace"
       (workloads/create-workload!
        (workloads/covid-workload-request
         {:skipValidation true}
         {:skipValidation true}
         {:workspace   "moo/moo"
          :entity      "moo"
          :identity    "reads_id"
          :fromOutputs {:submission_xml "submission_xml"}})))))

(deftest test-start-workload
  (let [workload (workloads/create-workload!
                  (workloads/covid-workload-request
                   {:skipValidation true}
                   {:skipValidation true}
                   {:skipValidation true}))]
    (is (not (:started workload)))
    (is (:started (workloads/start-workload! workload)))))

(defn ^:private create-tdr-source []
  (jdbc/with-db-transaction [tx (postgres/wfl-db-config)]
    (->> {:name           "Terra DataRepo",
          :dataset        "this"
          :table          "is"
          :column         "fun"
          :skipValidation true}
         (covid/create-source! tx (rand-int 1000000))
         (zipmap [:source_type :source_items])
         (covid/load-source! tx))))

(defn ^:private reload-source [tx {:keys [type id] :as _source}]
  (covid/load-source! tx {:source_type type :source_items (str id)}))

(deftest test-start-tdr-source
  (let [source (create-tdr-source)]
    (is (-> source :last_checked nil?))
    (jdbc/with-db-transaction [tx (postgres/wfl-db-config)]
      (covid/start-source! tx source)
      (is (:last_checked (reload-source tx source))
          ":last_checked was not updated"))))

(deftest test-update-tdr-source
  (let [source               (create-tdr-source)
        expected-num-records (int (Math/ceil (/ mock-new-rows-size 500)))]
    (with-redefs-fn
      {#'covid/create-snapshots mock-create-snapshots
       #'covid/find-new-rows    mock-find-new-rows
       #'covid/check-tdr-job    mock-check-tdr-job}
      (fn []
        (covid/update-source!
         (jdbc/with-db-transaction [tx (postgres/wfl-db-config)]
           (covid/start-source! tx source)
           (reload-source tx source)))
        (is (== expected-num-records (covid/queue-length! source))
            "snapshots should be enqueued")
        (jdbc/with-db-transaction [tx (postgres/wfl-db-config)]
          (let [records (->> source :details (postgres/get-table tx))]
            (letfn [(record-updated? [record]
                      (and (= "succeeded" (:snapshot_creation_job_status record))
                           (not (nil? (:snapshot_creation_job_id record)))
                           (not (nil? (:snapshot_id record)))))]
              (testing "source details got updated with correct number of snapshot jobs"
                (is (= expected-num-records (count records))))
              (testing "all snapshot jobs were updated and corresponding snapshot ids were inserted"
                (is (every? record-updated? records))))))
        (covid/update-source!
         (jdbc/with-db-transaction [tx (postgres/wfl-db-config)]
           (covid/stop-source! tx source)
           (reload-source tx source)))
        (is (== expected-num-records (covid/queue-length! source))
            "no more snapshots should be enqueued")))))

(deftest test-stop-tdr-source
  (let [source (create-tdr-source)]
    (jdbc/with-db-transaction [tx (postgres/wfl-db-config)]
      (covid/start-source! tx source)
      (covid/stop-source! tx source)
      (let [source (reload-source tx source)]
        (is (:stopped (reload-source tx source)) ":stopped was not written")))))

(defn ^:private create-tdr-snapshot-list [snapshots]
  (jdbc/with-db-transaction [tx (postgres/wfl-db-config)]
    (->> {:name           "TDR Snapshots"
          :snapshots      snapshots
          :skipValidation true}
         (covid/create-source! tx (rand-int 1000000))
         (zipmap [:source_type :source_items])
         (covid/load-source! tx))))

(defn ^:private create-terra-executor [id]
  (jdbc/with-db-transaction [tx (postgres/wfl-db-config)]
    (->> {:name                       "Terra"
          :workspace                  "workspace-ns/workspace-name"
          :methodConfiguration        "mc-namespace/mc-name"
          :methodConfigurationVersion methodConfigVersion
          :fromSource                 "importSnapshot"
          :skipValidation             true}
         (covid/create-executor! tx id)
         (zipmap [:executor_type :executor_items])
         (covid/load-executor! tx))))

(deftest test-update-terra-executor
  (let [source   (create-tdr-snapshot-list [snapshot])
        executor (create-terra-executor (rand-int 1000000))]
    (letfn [(verify-record-against-workflow [record workflow idx]
              (is (= idx (:id record))
                  "The record ID was incorrect given the workflow order in mocked submission")
              (is (= (:workflowId workflow) (:workflow record))
                  "The workflow ID was incorrect and should match corresponding record"))]
      (with-redefs-fn
        {#'rawls/create-snapshot-reference       mock-rawls-create-snapshot-reference
         #'firecloud/get-method-configuration    mock-firecloud-get-method-configuration
         #'firecloud/update-method-configuration mock-firecloud-update-method-configuration
         #'firecloud/submit-method               mock-firecloud-create-submission
         #'firecloud/get-submission              mock-firecloud-get-submission
         #'firecloud/get-workflow                mock-workflow-update-status}
        #(covid/update-executor! source executor))
      (is (zero? (covid/queue-length! source)) "The snapshot was not consumed.")
      (is (== 2 (covid/queue-length! executor)) "Two workflows should be enqueued")
      (jdbc/with-db-transaction [tx (postgres/wfl-db-config)]
        (let [[running-record succeeded-record & _ :as records]
              (->> executor :details (postgres/get-table tx) (sort-by :id))
              executor-record
              (#'covid/load-record-by-id! tx "TerraExecutor" (:id executor))]
          (is (== 2 (count records))
              "Exactly 2 workflows should have been written to the database")
          (is (every? #(= snapshot-reference-id (:reference %)) records)
              "The snapshot reference ID was incorrect and should match all records")
          (is (every? #(= submission-id (:submission %)) records)
              "The submission ID was incorrect and should match all records")
          (is (every? #(= "Succeeded" (:status %)) records)
              "Status update mock should have marked running workflow as succeeded")
          (is (every? #(nil? (:consumed %)) records)
              "All records should be unconsumed")
          (verify-record-against-workflow running-record running-workflow 1)
          (verify-record-against-workflow succeeded-record succeeded-workflow 2)
          (is (== (inc methodConfigVersion) (:method_configuration_version executor-record))
              "Method configuration version was not incremented."))))))

(deftest test-peek-terra-executor-queue
  (let [succeeded? #{"Succeeded"}
        source     (create-tdr-snapshot-list [snapshot])
        executor   (create-terra-executor (rand-int 1000000))]
    (with-redefs-fn
      {#'rawls/create-snapshot-reference       mock-rawls-create-snapshot-reference
       #'firecloud/get-method-configuration    mock-firecloud-get-method-configuration
       #'firecloud/update-method-configuration mock-firecloud-update-method-configuration
       #'firecloud/submit-method               mock-firecloud-create-submission
       #'firecloud/get-submission              mock-firecloud-get-submission
       #'firecloud/get-workflow                mock-workflow-keep-status}
      #(covid/update-executor! source executor))
    (with-redefs-fn
      {#'covid/peek-terra-executor-queue #'covid/peek-terra-executor-details}
      #(do (is (succeeded? (-> executor covid/peek-queue! :status)))
           (covid/pop-queue! executor)
           (is (nil? (covid/peek-queue! executor)))))))

(deftest test-update-terra-workspace-sink
  (let [flowcell-id "test"
        entity-type "flowcell"
        workflow    {:uuid    "2768b29e-c808-4bd6-a46b-6c94fd2a67aa"
                     :status  "Succeeded"
                     :outputs (-> "sarscov2_illumina_full/outputs.edn"
                                  resources/read-resource
                                  (assoc :flowcell_id flowcell-id))}
        executor    (make-queue-from-list [workflow])
        sink        (jdbc/with-db-transaction [tx (postgres/wfl-db-config)]
                      (->> {:name           "Terra Workspace"
                            :workspace      "workspace-ns/workspace-name"
                            :entity         entity-type
                            :fromOutputs    (resources/read-resource
                                             "sarscov2_illumina_full/entity-from-outputs.edn")
                            :identifier     "flowcell_id"
                            :skipValidation true}
                           (covid/create-sink! tx (rand-int 1000000))
                           (zipmap [:sink_type :sink_items])
                           (covid/load-sink! tx)))]
    (letfn [(verify-upsert-request [workspace [[name type _]]]
              (is (= "workspace-ns/workspace-name" workspace))
              (is (= name flowcell-id))
              (is (= type entity-type)))]
      (with-redefs-fn
        {#'rawls/batch-upsert verify-upsert-request}
        #(covid/update-sink! executor sink))
      (is (-> executor :queue empty?) "The workflow was not consumed")
      (jdbc/with-db-transaction [tx (postgres/wfl-db-config)]
        (let [[record & rest] (->> sink :details (postgres/get-table tx))]
          (is record
              "The record was not written to the database")
          (is (empty? rest) "More than one record was written")
          (is (= (:uuid workflow) (:workflow record))
              "The workflow UUID was not written")
          (is (= flowcell-id (:entity_name record))
              "The entity name was not correct"))))))

(deftest test-tdr-snapshot-list-to-edn
  (let [source (util/to-edn (create-tdr-snapshot-list [snapshot]))]
    (is (not-any? source [:id :type]))
    (is (= (:snapshots source) [(:id snapshot)]))
    (is (s/valid? ::spec/snapshot-list-source source))))

(deftest test-get-workflows-empty
  (let [workload (workloads/create-workload!
                  (workloads/covid-workload-request
                   {:skipValidation true}
                   {:skipValidation true}
                   {:skipValidation true}))]
    (is (empty? (workloads/workflows workload)))))

(deftest test-workload-state-transition
  (with-redefs-fn
    {#'covid/find-new-rows                   mock-find-new-rows
     #'covid/create-snapshots                mock-create-snapshots
     #'covid/check-tdr-job                   mock-check-tdr-job
     #'rawls/create-snapshot-reference       mock-rawls-create-snapshot-reference
     #'firecloud/get-method-configuration    mock-firecloud-get-method-configuration
     #'firecloud/update-method-configuration mock-firecloud-update-method-configuration
     #'firecloud/submit-method               mock-firecloud-create-submission
     #'firecloud/get-submission              mock-firecloud-get-submission
     #'firecloud/get-workflow                mock-workflow-keep-status}
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
    {#'covid/find-new-rows                   mock-find-new-rows
     #'covid/create-snapshots                mock-create-snapshots
     #'covid/check-tdr-job                   mock-check-tdr-job
     #'rawls/create-snapshot-reference       mock-rawls-create-snapshot-reference
     #'firecloud/get-method-configuration    mock-firecloud-get-method-configuration
     #'firecloud/update-method-configuration mock-firecloud-update-method-configuration
     #'firecloud/submit-method               mock-firecloud-create-submission
     #'firecloud/get-workflow                (constantly {:status "Failed"})}
    #(shared/run-workload-state-transition-test!
      (workloads/covid-workload-request
       {:skipValidation true}
       {:skipValidation true}
       {:skipValidation true}))))

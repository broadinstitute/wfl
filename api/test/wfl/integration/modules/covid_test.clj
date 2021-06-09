(ns wfl.integration.modules.covid-test
  "Test the Sarscov2IlluminaFull COVID pipeline."
  (:require [clojure.test                   :refer :all]
            [clojure.set                    :as set]
            [clojure.spec.alpha             :as s]
            [clojure.string                 :as str]
            [reitit.coercion.spec]
            [reitit.ring                    :as ring]
            [reitit.ring.coercion           :as coercion]
            [wfl.api.spec                   :as spec]
            [wfl.api.spec                   :as spec]
            [wfl.integration.modules.shared :as shared]
            [wfl.jdbc                       :as jdbc]
            [wfl.module.covid               :as covid]
            [wfl.service.firecloud          :as firecloud]
            [wfl.service.postgres           :as postgres]
            [wfl.service.rawls              :as rawls]
            [wfl.stage                      :as stage]
            [wfl.tools.fixtures             :as fixtures]
            [wfl.tools.workloads            :as workloads]
            [wfl.tools.resources            :as resources]
            [wfl.util                       :as util])
  (:import [java.lang Math]
           [java.time LocalDateTime]
           [java.util ArrayDeque UUID]
           [wfl.util UserException]))

;; Snapshot creation mock
(def ^:private mock-new-rows-size 2021)
(defn ^:private mock-find-new-rows [_ interval]
  (is (every? #(LocalDateTime/parse % @#'covid/bigquery-datetime-format) interval))
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
(def ^:private testing-workspace "wfl-dev/CDC_Viral_Sequencing")
(def ^:private testing-method-name "sarscov2_illumina_full")
(def ^:private testing-method-configuration (str "cdc-covid-surveillance/" testing-method-name))
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
                  {:skipValidation true})
        _        (is (s/valid? ::spec/workload-request request)
                     (s/explain-str ::spec/workload-request request))
        workload (util/to-edn (workloads/create-workload! request))]
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

(deftest test-create-covid-workload-with-invalid-executor-request
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

(deftest test-create-covid-workload-with-wrong-method-configuration-1
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
         :entityType  "assemblies"
         :identity    "Who cares?"
         :fromOutputs {:assemblies_id "foo"}}))))

(deftest test-create-covid-workload-with-invalid-sink-entity-type
  (let [request (workloads/covid-workload-request
                 {:skipValidation true}
                 {:skipValidation true}
                 {:workspace   testing-workspace
                  :entityType  "does_not_exist"
                  :identity    "Who cares?"
                  :fromOutputs {}})]
    (is (thrown-with-msg?
         UserException (re-pattern covid/unknown-entity-type-error-message)
         (workloads/create-workload! request)))))

(deftest test-create-covid-workload-with-malformed-fromOutputs
  (let [request (workloads/covid-workload-request
                 {:skipValidation true}
                 {:skipValidation true}
                 {:workspace   testing-workspace
                  :entityType  "assemblies"
                  :identity    "Who cares?"
                  :fromOutputs "geoff"})]
    (is (thrown-with-msg?
         UserException (re-pattern covid/malformed-from-outputs-error-message)
         (workloads/create-workload! request)))))

(deftest test-create-covid-workload-with-unknown-attributes-listed-in-fromOutputs
  (let [request (workloads/covid-workload-request
                 {:skipValidation true}
                 {:skipValidation true}
                 {:workspace   testing-workspace
                  :entityType  "assemblies"
                  :identity    "Who cares?"
                  :fromOutputs {:does_not_exist "genbank_source_table"}})]
    (is (thrown-with-msg?
         UserException (re-pattern covid/unknown-attributes-error-message)
         (workloads/create-workload! request)))))

(deftest test-create-covid-workload-with-invalid-sink-workspace
  (is (thrown-with-msg?
       UserException #"Cannot access workspace"
       (workloads/create-workload!
        (workloads/covid-workload-request
         {:skipValidation true}
         {:skipValidation true}
         {:workspace   "moo/moo"
          :entityType  "moo"
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

;; Mocks

(def ^:private fake-method-name "method-name")
(def ^:private fake-method-config (str "method-namespace/" fake-method-name))
;; Snapshot and snapshot reference mocks
(def ^:private snapshot
  {:name "test-snapshot-name" :id (str (UUID/randomUUID))})

(def ^:private snapshot-reference-id (str (UUID/randomUUID)))
(def ^:private snapshot-reference-name (str (:name snapshot) "-ref"))
(defn ^:private mock-rawls-create-snapshot-reference [& _]
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
  (let [enqueue #(-> % (dissoc :id) (assoc :staus "Queued"))]
    {:submissionId submission-id-mock
     :workflows    (map enqueue [running-workflow-mock succeeded-workflow-mock])}))

;; when we get the submission later, the workflows may have a uuid assigned
(defn ^:private mock-firecloud-get-submission [& _]
  (letfn [(add-workflow-entity [{:keys [entityName] :as workflow}]
            (-> workflow
                (set/rename-keys {:id :workflowId})
                (assoc :workflowEntity {:entityType "test" :entityName entityName})
                (dissoc :entityName)))]
    {:submissionId submission-id-mock
     :workflows   (map add-workflow-entity [running-workflow-mock succeeded-workflow-mock])}))

(defn ^:private mock-firecloud-create-failed-submission [& _]
  {:submissionId submission-id-mock
   :workflows    [{:status     "Failed"
                   :uuid       (str (UUID/randomUUID))
                   :entityName "failed"}
                  {:status     "Aborted"
                   :uuid       (str (UUID/randomUUID))
                   :entityName "aborted"}]})

;; Workflow fetch mocks within update-workflow-statuses!
(defn ^:private mock-workflow-update-status [_ _ workflow-id]
  (is (not (= (:workflowId succeeded-workflow-mock) workflow-id))
      "Successful workflow records should be filtered out before firecloud fetch")
  {:status "Succeeded" :id workflow-id :workflowName fake-method-name})

(defn ^:private mock-workflow-keep-status [_ _ workflow-id]
  (is (not (= (:workflowId succeeded-workflow-mock) workflow-id))
      "Successful workflow records should be filtered out before firecloud fetch")
  running-workflow-mock)

(defn ^:private mock-firecloud-get-workflow-outputs [_ _ workflow]
  (is (= (:id succeeded-workflow-mock) workflow))
  {:tasks
   {:noise
    {}
    (keyword fake-method-name)
    {:outputs
     (util/prefix-keys {:output "value"} (str fake-method-name "."))}}})

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
        (is (== expected-num-records (stage/queue-length source))
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
        (is (== expected-num-records (stage/queue-length source))
            "no more snapshots should be enqueued")
        (is (not (stage/done? source)) "the tdr source was done before snapshots were consumed")))))

(deftest test-stop-tdr-sourced
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
          :methodConfiguration        fake-method-config
          :methodConfigurationVersion method-config-version-mock
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
              (is (= (:id workflow) (:workflow record))
                  "The workflow ID was incorrect and should match corresponding record"))]
      (with-redefs-fn
        {#'rawls/create-snapshot-reference       mock-rawls-create-snapshot-reference
         #'firecloud/get-method-configuration    mock-firecloud-get-method-configuration
         #'firecloud/update-method-configuration mock-firecloud-update-method-configuration
         #'firecloud/submit-method               mock-firecloud-create-submission
         #'firecloud/get-submission              mock-firecloud-get-submission
         #'firecloud/get-workflow                mock-workflow-update-status}
        #(covid/update-executor! source executor))
      (is (zero? (stage/queue-length source)) "The snapshot was not consumed.")
      (is (== 2 (stage/queue-length executor)) "Two workflows should be enqueued")
      (jdbc/with-db-transaction [tx (postgres/wfl-db-config)]
        (let [[running-record succeeded-record & _ :as records]
              (->> executor :details (postgres/get-table tx) (sort-by :id))
              executor-record
              (#'covid/load-record-by-id! tx "TerraExecutor" (:id executor))]
          (is (== 2 (count records))
              "Exactly 2 workflows should have been written to the database")
          (is (every? #(= snapshot-reference-id (:reference %)) records)
              "The snapshot reference ID was incorrect and should match all records")
          (is (every? #(= submission-id-mock (:submission %)) records)
              "The submission ID was incorrect and should match all records")
          (is (every? #(= "Succeeded" (:status %)) records)
              "Status update mock should have marked running workflow as succeeded")
          (is (every? #(nil? (:consumed %)) records)
              "All records should be unconsumed")
          (is (not (stage/done? executor)) "executor should not have finished processing")
          (verify-record-against-workflow running-record running-workflow-mock 1)
          (verify-record-against-workflow succeeded-record succeeded-workflow-mock 2)
          (is (== (inc method-config-version-mock) (:method_configuration_version executor-record))
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
      {#'firecloud/get-workflow         (constantly succeeded-workflow-mock)
       #'firecloud/get-workflow-outputs mock-firecloud-get-workflow-outputs}
      #(let [workflow (stage/peek-queue executor)]
         (is (succeeded? (:status workflow)))
         (is (= (:id succeeded-workflow-mock) (:uuid workflow)))
         (is (contains? workflow :updated))
         (is (= "value" (-> workflow :inputs :input)))
         (is (= "value" (-> workflow :outputs :output)))
         (is (not (-> workflow :outputs :noise)))
         (stage/pop-queue! executor)
         (is (nil? (stage/peek-queue executor)))
         (is (== 1 (stage/queue-length executor)))
         (is (not (stage/done? executor)))))))

(def ^:private fake-entity-type "flowcell")
(def ^:private fake-entity-name "test")

(deftest test-update-terra-workspace-sink
  (let [workflow    {:uuid    "2768b29e-c808-4bd6-a46b-6c94fd2a67aa"
                     :status  "Succeeded"
                     :outputs (-> "sarscov2_illumina_full/outputs.edn"
                                  resources/read-resource
                                  (assoc :flowcell_id fake-entity-name))}
        executor    (make-queue-from-list [workflow])
        sink        (jdbc/with-db-transaction [tx (postgres/wfl-db-config)]
                      (->> {:name           "Terra Workspace"
                            :workspace      "workspace-ns/workspace-name"
                            :entityType     fake-entity-type
                            :fromOutputs    (resources/read-resource
                                             "sarscov2_illumina_full/entity-from-outputs.edn")
                            :identifier     "flowcell_id"
                            :skipValidation true}
                           (covid/create-sink! tx (rand-int 1000000))
                           (zipmap [:sink_type :sink_items])
                           (covid/load-sink! tx)))]
    (letfn [(verify-upsert-request [workspace [[type name _]]]
              (is (= "workspace-ns/workspace-name" workspace))
              (is (= type fake-entity-type))
              (is (= name fake-entity-name)))
            (throw-if-called [fname & args]
              (throw (ex-info (str fname " should not have been called")
                              {:called-with args})))]
      (with-redefs-fn
        {#'rawls/batch-upsert        verify-upsert-request
         #'covid/entity-exists?      (constantly false)
         #'firecloud/delete-entities (partial throw-if-called "delete-entities")}
        #(covid/update-sink! executor sink))
      (is (zero? (stage/queue-length executor)) "The workflow was not consumed")
      (jdbc/with-db-transaction [tx (postgres/wfl-db-config)]
        (let [[record & rest] (->> sink :details (postgres/get-table tx))]
          (is record "The record was not written to the database")
          (is (empty? rest) "More than one record was written")
          (is (= (:uuid workflow) (:workflow record))
              "The workflow UUID was not written")
          (is (= fake-entity-name (:entity record))
              "The entity was not correct"))))))

(deftest test-sinking-resubmitted-workflow
  (fixtures/with-temporary-workspace
    (fn [workspace]
      (let [workflow1 {:uuid    "2768b29e-c808-4bd6-a46b-6c94fd2a67aa"
                       :status  "Succeeded"
                       :outputs {:run_id  fake-entity-name
                                 :results ["aligned-thing.cram"]}}
            workflow2 {:uuid    "2768b29e-c808-4bd6-a46b-6c94fd2a67ab"
                       :status  "Succeeded"
                       :outputs {:run_id  fake-entity-name
                                 :results ["another-aligned-thing.cram"]}}
            executor  (make-queue-from-list [workflow1 workflow2])
            sink      (jdbc/with-db-transaction [tx (postgres/wfl-db-config)]
                        (->> {:name           "Terra Workspace"
                              :workspace      workspace
                              :entityType     fake-entity-type
                              :fromOutputs    {:aligned_crams "results"}
                              :identifier     "run_id"
                              :skipValidation true}
                             (covid/create-sink! tx (rand-int 1000000))
                             (zipmap [:sink_type :sink_items])
                             (covid/load-sink! tx)))]
        (covid/update-sink! executor sink)
        (is (== 1 (stage/queue-length executor))
            "one workflow should have been consumed")
        (let [{:keys [entityType name attributes]}
              (firecloud/get-entity workspace [fake-entity-type fake-entity-name])]
          (is (= fake-entity-type entityType))
          (is (= fake-entity-name name))
          (is (== 1 (count attributes)))
          (is (= [:aligned_crams {:itemsType "AttributeValue" :items ["aligned-thing.cram"]}]
                 (first attributes))))
        (covid/update-sink! executor sink)
        (is (zero? (stage/queue-length executor))
            "one workflow should have been consumed")
        (let [entites (firecloud/list-entities workspace fake-entity-type)]
          (is (== 1 (count entites))
              "No new entities should have been added"))
        (let [{:keys [entityType name attributes]}
              (firecloud/get-entity workspace [fake-entity-type fake-entity-name])]
          (is (= fake-entity-type entityType))
          (is (= fake-entity-name name))
          (is (== 1 (count attributes)))
          (is (= [:aligned_crams {:itemsType "AttributeValue" :items ["another-aligned-thing.cram"]}]
                 (first attributes))
              "attributes should have been overwritten"))))))

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

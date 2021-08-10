(ns wfl.integration.executor-test
  (:require [clojure.test          :refer [deftest is use-fixtures]]
            [clojure.set           :as set]
            [wfl.executor          :as executor]
            [wfl.jdbc              :as jdbc]
            [wfl.service.firecloud :as firecloud]
            [wfl.service.postgres  :as postgres]
            [wfl.service.rawls     :as rawls]
            [wfl.stage             :as stage]
            [wfl.tools.fixtures    :as fixtures]
            [wfl.tools.queues      :refer [make-queue-from-list]]
            [wfl.util              :as util])
  (:import [java.util UUID]
           [wfl.util UserException]))

(def ^:private testing-namespace "wfl-dev")
(def ^:private testing-workspace (str testing-namespace "/" "CDC_Viral_Sequencing"))
(def ^:private testing-method-name "sarscov2_illumina_full")
(def ^:private testing-method-configuration (str testing-namespace "/" testing-method-name))
(def ^:private testing-method-configuration-version 1)

(let [new-env {"WFL_FIRECLOUD_URL" "https://api.firecloud.org"
               "WFL_RAWLS_URL"     "https://rawls.dsde-prod.broadinstitute.org"}]
  (use-fixtures :once
    (fixtures/temporary-environment new-env)
    fixtures/temporary-postgresql-database))

(deftest test-validate-terra-executor-with-valid-executor-request
  (is (executor/validate-or-throw
       {:name                       "Terra"
        :workspace                  testing-workspace
        :methodConfiguration        testing-method-configuration
        :methodConfigurationVersion testing-method-configuration-version
        :fromSource                 "importSnapshot"})))

(deftest test-validate-terra-executor-with-misnamed-executor
  (is (thrown-with-msg?
       UserException #"Invalid request"
       (executor/validate-or-throw
        {:name "bad name"}))))

(deftest test-validate-terra-executor-with-invalid-executor-request
  (is (thrown-with-msg?
       UserException #"Unsupported coercion"
       (executor/validate-or-throw
        {:name                       "Terra"
         :workspace                  testing-workspace
         :methodConfiguration        testing-method-configuration
         :methodConfigurationVersion testing-method-configuration-version
         :fromSource                 "frobnicate"}))))

(deftest test-validate-terra-executor-with-wrong-method-configuration-version
  (is (thrown-with-msg?
       UserException #"Unexpected method configuration version"
       (executor/validate-or-throw
        {:name                       "Terra"
         :workspace                  testing-workspace
         :methodConfiguration        testing-method-configuration
         :methodConfigurationVersion -1
         :fromSource                 "importSnapshot"}))))

(deftest test-validate-terra-executor-with-wrong-method-configuration
  (is (thrown-with-msg?
       UserException #"Cannot access method configuration"
       (executor/validate-or-throw
        {:name                "Terra"
         :workspace           testing-workspace
         :methodConfiguration "no_such/method_configuration"
         :fromSource          "importSnapshot"}))))

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

(defn ^:private create-terra-executor [id]
  (jdbc/with-db-transaction [tx (postgres/wfl-db-config)]
    (->> {:name                       "Terra"
          :workspace                  "workspace-ns/workspace-name"
          :methodConfiguration        fake-method-config
          :methodConfigurationVersion method-config-version-mock
          :fromSource                 "importSnapshot"
          :skipValidation             true}
         (executor/create-executor! tx id)
         (zipmap [:executor_type :executor_items])
         (executor/load-executor! tx))))

(deftest test-update-terra-executor
  (let [source   (make-queue-from-list [[:datarepo/snapshot snapshot]])
        executor (create-terra-executor (rand-int 1000000))]
    (letfn [(verify-record-against-workflow [record workflow idx]
              (is (= idx (:id record))
                  "The record ID was incorrect given the workflow order in mocked submission")
              (is (= (:id workflow) (:workflow record))
                  "The workflow ID was incorrect and should match corresponding record"))]
      (with-redefs-fn
        {#'rawls/create-snapshot-reference       mock-rawls-create-snapshot-reference
         #'firecloud/method-configuration        mock-firecloud-get-method-configuration
         #'firecloud/update-method-configuration mock-firecloud-update-method-configuration
         #'firecloud/submit-method               mock-firecloud-create-submission
         #'firecloud/get-submission              mock-firecloud-get-submission
         #'firecloud/get-workflow                mock-workflow-update-status}
        #(executor/update-executor! source executor))
      (is (zero? (stage/queue-length source)) "The snapshot was not consumed.")
      (is (== 2 (stage/queue-length executor)) "Two workflows should be enqueued")
      (jdbc/with-db-transaction [tx (postgres/wfl-db-config)]
        (let [[running-record succeeded-record & _ :as records]
              (->> executor :details (postgres/get-table tx) (sort-by :id))
              executor-record
              (#'postgres/load-record-by-id! tx "TerraExecutor" (:id executor))]
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
        source     (make-queue-from-list [[:datarepo/snapshot snapshot]])
        executor   (create-terra-executor (rand-int 1000000))]
    (with-redefs-fn
      {#'rawls/create-snapshot-reference       mock-rawls-create-snapshot-reference
       #'firecloud/method-configuration        mock-firecloud-get-method-configuration
       #'firecloud/update-method-configuration mock-firecloud-update-method-configuration
       #'firecloud/submit-method               mock-firecloud-create-submission
       #'firecloud/get-submission              mock-firecloud-get-submission
       #'firecloud/get-workflow                mock-workflow-keep-status}
      #(executor/update-executor! source executor))
    (with-redefs-fn
      {#'executor/describe-method       (constantly nil)
       #'firecloud/get-workflow         (constantly succeeded-workflow-mock)
       #'firecloud/get-workflow-outputs mock-firecloud-get-workflow-outputs}
      #(let [[_ workflow] (stage/peek-queue executor)]
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

(deftest test-terra-executor-get-retried-workflows
  (with-redefs-fn
    {#'rawls/create-snapshot-reference       mock-rawls-create-snapshot-reference
     #'firecloud/method-configuration        mock-firecloud-get-method-configuration
     #'firecloud/update-method-configuration mock-firecloud-update-method-configuration
     #'firecloud/submit-method               mock-firecloud-create-submission
     #'firecloud/get-submission              mock-firecloud-get-submission
     #'firecloud/get-workflow                mock-workflow-keep-status
     #'firecloud/get-workflow-outputs        mock-firecloud-get-workflow-outputs}
    #(let [source   (make-queue-from-list [[:datarepo/snapshot snapshot]])
           executor (create-terra-executor (rand-int 1000000))]
       (executor/update-executor! source executor)
       (is (== 2 (stage/queue-length executor)))
       (jdbc/with-db-transaction [tx (postgres/wfl-db-config)]
         (jdbc/update! tx (:details executor) {:retry 2} ["id = ?" 1]))
       (is (== 2 (stage/queue-length executor))
           "The retried workflow should remain visible downstream")
       (jdbc/with-db-transaction [tx (postgres/wfl-db-config)]
         (is (== 1 (count (executor/executor-workflows tx executor)))
             "The retried workflow should not be returned")))))

(deftest test-tdr-executor-describe-method
  (let [description (executor/describe-method
                     testing-workspace
                     testing-method-configuration)]
    (is (every? description [:validWorkflow :inputs :outputs]))
    (is (= "sarscov2_illumina_full" (:name description)))))

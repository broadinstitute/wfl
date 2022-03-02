(ns wfl.integration.executor-test
  (:require [clojure.string        :as str]
            [clojure.test          :refer [deftest is testing use-fixtures]]
            [wfl.api.workloads     :as workloads]
            [wfl.executor          :as executor]
            [wfl.jdbc              :as jdbc]
            [wfl.service.datarepo  :as datarepo]
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
(def ^:private testing-method-configuration-version 2)

(let [new-env {"WFL_FIRECLOUD_URL" "https://api.firecloud.org"
               "WFL_RAWLS_URL"     "https://rawls.dsde-prod.broadinstitute.org"}]
  (use-fixtures :once
    (fixtures/temporary-environment new-env)
    fixtures/temporary-postgresql-database))

(deftest test-validate-terra-executor-with-valid-executor-request
  (let [request {:name                       "Terra"
                 :workspace                  testing-workspace
                 :methodConfiguration        testing-method-configuration
                 :fromSource                 "importSnapshot"}]
    (letfn [(check-mc-version [executor]
              (is executor)
              (is (== testing-method-configuration-version
                      (:methodConfigurationVersion executor))))]
      (testing "request without method configuration version"
        (-> request
            executor/terra-executor-validate-request-or-throw
            check-mc-version))
      (testing "request ignores specified method configuration version"
        (-> (assoc request :methodConfigurationVersion -1)
            executor/terra-executor-validate-request-or-throw
            check-mc-version)))))

(deftest test-validate-terra-executor-with-invalid-executor-request
  (is (thrown-with-msg?
       UserException #"Unsupported coercion"
       (executor/terra-executor-validate-request-or-throw
        {:name                       "Terra"
         :workspace                  testing-workspace
         :methodConfiguration        testing-method-configuration
         :fromSource                 "frobnicate"}))))



(deftest test-validate-terra-executor-with-wrong-method-configuration
  (is (thrown-with-msg?
       UserException #"Cannot access method configuration"
       (executor/terra-executor-validate-request-or-throw
        {:name                "Terra"
         :workspace           testing-workspace
         :methodConfiguration "no_such/method_configuration"
         :fromSource          "importSnapshot"}))))

;; Mocks

;; Snapshot and snapshot reference mocks
(def ^:private snapshot-name "test-snapshot-name")
(def ^:private snapshot-table-name "snapshot-table")
(defn ^:private mock-datarepo-snapshot [& _]
  {:name   snapshot-name
   :id     (str (UUID/randomUUID))
   :tables [{:name snapshot-table-name}]})

(def ^:private snapshot-reference-id (str (UUID/randomUUID)))
(defn ^:private mock-rawls-snapshot-reference [& _]
  {:metadata {:name            snapshot-name
              :resourceId      snapshot-reference-id
              :resourceType    "DATA_REPO_SNAPSHOT"
              :stewardshipType "REFERENCED"}})

;; Method configuration
(def ^:private fake-method-name "method-name")
(def ^:private fake-method-config (str "method-namespace/" fake-method-name))

(def ^:private method-config-version-init 1)
(def ^:private method-config-version-post-update 2)

(defn ^:private mock-firecloud-get-method-configuration [method-config-version]
  {:methodConfigVersion method-config-version})

(defn ^:private mock-firecloud-get-method-configuration-init [& _]
  (mock-firecloud-get-method-configuration method-config-version-init))
(defn ^:private mock-firecloud-get-method-configuration-post-update [& _]
  (mock-firecloud-get-method-configuration method-config-version-post-update))

(defn ^:private mock-firecloud-update-method-configuration [method-config-version]
  (fn [_ _ {:keys [dataReferenceName methodConfigVersion]}]
    (is (= dataReferenceName snapshot-name)
        "Snapshot reference name should be passed to method config update")
    (is (= methodConfigVersion (inc method-config-version))
        "Incremented version should be passed to method config update")
    nil))

(defn ^:private mock-firecloud-update-method-configuration-init [& _]
  (mock-firecloud-update-method-configuration method-config-version-init))
(defn ^:private mock-firecloud-update-method-configuration-post-update [& _]
  (mock-firecloud-update-method-configuration method-config-version-post-update))

;; Submissions and workflows

(defn ^:private workflow-base [entity status]
  {:entity      entity
   :workflow-id (str (UUID/randomUUID))
   :status      status})

(def ^:private init-submission-id  "init-submission-id")
(def ^:private retry-submission-id "retry-submission-id")

(def ^:private submission-base
  (let [running-entity   "running-entity"
        succeeded-entity "succeeded-entity"]
    {init-submission-id  {:running   (workflow-base running-entity "Running")
                          :succeeded (workflow-base succeeded-entity "Succeeded")}
     retry-submission-id {:running   (workflow-base running-entity "Running")
                          :succeeded (workflow-base succeeded-entity "Succeeded")}}))

;; Firecloud workflow format differs based on whether it is fetched
;; at the submission level or the workflow level.
(defn ^:private mock-firecloud-get-submission-workflow
  [{:keys [entity workflow-id status] :as _workflow-base}]
  {:entityName       entity
   :inputResolutions {:inputName (str fake-method-name ".input")
                      :value     "value"}
   :status           status
   :workflowId       workflow-id})

(defn ^:private mock-firecloud-get-workflow
  [{:keys [workflow-id status] :as _workflow-base}]
  {:id           workflow-id
   :inputs       {:input "value"}
   :status       status
   :workflowName fake-method-name})

(defn ^:private running-workflow-from-submission [submission-id]
  (mock-firecloud-get-submission-workflow (get-in submission-base [submission-id :running])))

(defn ^:private succeeded-workflow-from-submission [submission-id]
  (mock-firecloud-get-submission-workflow (get-in submission-base [submission-id :succeeded])))

;; When we create submissions, workflows have no uuid or status.
(defn ^:private mock-firecloud-create-submission [submission-id]
  (fn [& _]
    (let [enqueue #(dissoc % :workflowId :status)]
      {:submissionId submission-id
       :workflows    (map enqueue [(running-workflow-from-submission submission-id)
                                   (succeeded-workflow-from-submission submission-id)])})))

;; When we get the submission later, the workflows may have a uuid and status assigned.
(defn ^:private mock-firecloud-get-submission [_ submission-id]
  (letfn [(add-workflow-entity [{:keys [entityName] :as workflow}]
            (-> workflow
                (assoc :workflowEntity {:entityType "test" :entityName entityName})
                (dissoc :entityName)))]
    {:submissionId submission-id
     :workflows    (map add-workflow-entity [(running-workflow-from-submission submission-id)
                                             (succeeded-workflow-from-submission submission-id)])}))

;; Workflow fetch mocks within update-workflow-statuses!
(defn ^:private mock-firecloud-get-running-workflow-update-status
  [_ submission-id workflow-id & _]
  (let [workflow-base (get-in submission-base [submission-id :running])]
    (is (= (:workflow-id workflow-base) workflow-id)
        "Expecting to fetch and update status for running workflow")
    (assoc (mock-firecloud-get-workflow workflow-base) :status "Succeeded")))

(defn ^:private mock-firecloud-get-known-workflow
  [_ submission-id workflow-id & _]
  (if-let [workflow-base (->> (get submission-base submission-id)
                              vals
                              (filter #(= workflow-id (:workflow-id %)))
                              first)]
    (mock-firecloud-get-workflow workflow-base)
    (throw
     (ex-info "Workflow ID does not match known workflow"
              {:known-submissions submission-base
               :submission-id     submission-id
               :workflow-id       workflow-id}))))

(defn ^:private mock-firecloud-get-workflow-outputs [_ submission-id workflow-id]
  (is (= (get-in submission-base [submission-id :succeeded :workflow-id]) workflow-id)
      "Expecting to fetch outputs for successful workflow")
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
          :fromSource                 "importSnapshot"
          :skipValidation             true}
         (executor/create-executor! tx id)
         (zipmap [:executor_type :executor_items])
         (executor/load-executor! tx))))

(defn ^:private reload-terra-executor
  "Reload an established `executor` object."
  [{:keys [type id] :as _executor}]
  (let [workload (zipmap [:executor_type :executor_items] [type (str id)])]
    (jdbc/with-db-transaction [tx (postgres/wfl-db-config)]
      (executor/load-executor! tx workload))))

(defn ^:private mock-load-workload-for-uuid
  "Mock workloads/load-workload-for-uuid to return a mocked workload"
  [source executor]
  (fn [_ uuid] {:uuid uuid :source source :executor executor}))

(deftest test-update-terra-executor
  (let [source        (make-queue-from-list [[:datarepo/snapshot (mock-datarepo-snapshot)]])
        executor      (create-terra-executor (rand-int 1000000))
        workload-uuid (UUID/randomUUID)
        workload      {:uuid workload-uuid :source source :executor executor}]
    (letfn [(verify-record-against-workflow [record workflow idx]
              (is (= idx (:id record))
                  "The record ID was incorrect given the workflow order in mocked submission")
              (is (= (:workflowId workflow) (:workflow record))
                  "The workflow ID was incorrect and should match corresponding record"))]
      (with-redefs-fn
        {#'rawls/create-or-get-snapshot-reference mock-rawls-snapshot-reference
         #'firecloud/method-configuration         mock-firecloud-get-method-configuration-init
         #'datarepo/snapshot                      mock-datarepo-snapshot
         #'firecloud/update-method-configuration  mock-firecloud-update-method-configuration-init
         #'firecloud/submit-method                (mock-firecloud-create-submission init-submission-id)
         #'firecloud/get-submission               mock-firecloud-get-submission
         #'firecloud/get-workflow                 mock-firecloud-get-running-workflow-update-status
         #'workloads/load-workload-for-uuid       (mock-load-workload-for-uuid source executor)}
        #(executor/update-executor! workload))
      (is (zero? (stage/queue-length source)) "The snapshot was not consumed.")
      (is (== 2 (stage/queue-length executor)) "Two workflows should be enqueued")
      (jdbc/with-db-transaction [tx (postgres/wfl-db-config)]
        (let [[running-record succeeded-record & _ :as records]
              (->> executor :details (postgres/get-table tx) (sort-by :id))
              executor-record
              (#'postgres/load-record-by-id! tx "TerraExecutor" (:id executor))
              running-workflow (running-workflow-from-submission init-submission-id)
              succeeded-workflow (succeeded-workflow-from-submission init-submission-id)]
          (is (== 2 (count records))
              "Exactly 2 workflows should have been written to the database")
          (is (every? #(= snapshot-reference-id (:reference %)) records)
              "The snapshot reference ID was incorrect and should match all records")
          (is (every? #(= init-submission-id (:submission %)) records)
              "The submission ID was incorrect and should match all records")
          (is (every? #(= "Succeeded" (:status %)) records)
              "Status update mock should have marked running workflow as succeeded")
          (is (every? #(nil? (:consumed %)) records)
              "All records should be unconsumed")
          (is (not (stage/done? executor)) "executor should not have finished processing")
          (verify-record-against-workflow running-record running-workflow 1)
          (verify-record-against-workflow succeeded-record succeeded-workflow 2)
          (is (== method-config-version-post-update (:method_configuration_version executor-record))
              "Method configuration version was not incremented."))))))

(deftest test-peek-terra-executor-queue
  (let [succeeded?    #{"Succeeded"}
        source        (make-queue-from-list [[:datarepo/snapshot (mock-datarepo-snapshot)]])
        executor      (create-terra-executor (rand-int 1000000))
        workload-uuid (UUID/randomUUID)
        workload      {:uuid workload-uuid :source source :executor executor}]
    (with-redefs-fn
      {#'rawls/create-or-get-snapshot-reference mock-rawls-snapshot-reference
       #'firecloud/method-configuration         mock-firecloud-get-method-configuration-init
       #'datarepo/snapshot                      mock-datarepo-snapshot
       #'firecloud/update-method-configuration  mock-firecloud-update-method-configuration-init
       #'firecloud/submit-method                (mock-firecloud-create-submission init-submission-id)
       #'firecloud/get-submission               mock-firecloud-get-submission
       #'firecloud/get-workflow                 mock-firecloud-get-known-workflow
       #'workloads/load-workload-for-uuid       (mock-load-workload-for-uuid source executor)}
      #(executor/update-executor! workload))
    (with-redefs-fn
      {#'executor/describe-method       (constantly nil)
       #'firecloud/get-workflow         mock-firecloud-get-known-workflow
       #'firecloud/get-workflow-outputs mock-firecloud-get-workflow-outputs}
      #(let [[_ workflow] (stage/peek-queue executor)
             succeeded-workflow-id
             (get-in submission-base [init-submission-id :succeeded :workflow-id])]
         (is (succeeded? (:status workflow)))
         (is (= succeeded-workflow-id (:uuid workflow)))
         (is (contains? workflow :updated))
         (is (= "value" (-> workflow :inputs :input)))
         (is (= "value" (-> workflow :outputs :output)))
         (is (not (-> workflow :outputs :noise)))
         (stage/pop-queue! executor)
         (is (nil? (stage/peek-queue executor)))
         (is (== 1 (stage/queue-length executor)))
         (is (not (stage/done? executor)))))))

(deftest test-terra-executor-retry-workflows
  (let [source        (make-queue-from-list [[:datarepo/snapshot (mock-datarepo-snapshot)]])
        executor      (create-terra-executor (rand-int 1000000))
        workload-uuid (UUID/randomUUID)
        workload      {:uuid workload-uuid :source source :executor executor}]
    (with-redefs-fn
      {#'rawls/create-or-get-snapshot-reference mock-rawls-snapshot-reference
       #'firecloud/method-configuration         mock-firecloud-get-method-configuration-init
       #'datarepo/snapshot                      mock-datarepo-snapshot
       #'firecloud/update-method-configuration  mock-firecloud-update-method-configuration-init
       #'firecloud/submit-method                (mock-firecloud-create-submission init-submission-id)
       #'firecloud/get-submission               mock-firecloud-get-submission
       #'firecloud/get-workflow                 mock-firecloud-get-known-workflow
       #'workloads/load-workload-for-uuid       (mock-load-workload-for-uuid source executor)}
      #(executor/update-executor! workload))
    (is (zero? (stage/queue-length source)) "The snapshot was not consumed.")
    (is (== 2 (stage/queue-length executor))
        "Two workflows should be enqueued prior to retry.")
    (let [executor           (reload-terra-executor executor)
          workload-uuid        (UUID/randomUUID)
          workload             {:uuid workload-uuid :source source :executor executor}]
      (is (== 2 (:methodConfigurationVersion executor))
          "Reloaded executor's method config should have version 2 post-update.")
      (with-redefs-fn
        {#'rawls/get-snapshot-reference           mock-rawls-snapshot-reference
         #'firecloud/method-configuration         mock-firecloud-get-method-configuration-post-update
         #'datarepo/snapshot                      mock-datarepo-snapshot
         #'firecloud/update-method-configuration  mock-firecloud-update-method-configuration-post-update
         #'firecloud/submit-method                (mock-firecloud-create-submission retry-submission-id)
         #'firecloud/get-submission               mock-firecloud-get-submission
         #'firecloud/get-workflow                 mock-firecloud-get-known-workflow}
        #(let [workflows-to-retry
               (jdbc/with-db-transaction [tx (postgres/wfl-db-config)]
                 (executor/executor-workflows tx executor {:status "Running"}))]
           (is (== 1 (count workflows-to-retry))
               "Should have one running workflow to retry.")
           (executor/executor-retry-workflows! workload workflows-to-retry)))
      ;; We only specify 1 workflow to retry,
      ;; but must retry both workflows from its submission.
      (is (== 4 (stage/queue-length executor))
          "Four workflows should be enqueued following retry.")
      (let [[running succeeded retry-running retry-succeeded & _ :as records]
            (jdbc/with-db-transaction [tx (postgres/wfl-db-config)]
              (->> executor :details (postgres/get-table tx) (sort-by :id)))
            executor-record
            (jdbc/with-db-transaction [tx (postgres/wfl-db-config)]
              (#'postgres/load-record-by-id! tx "TerraExecutor" (:id executor)))
            compare-original-with-retry
            (fn [original retry status-kw]
              (is (= (:retry original) (:id retry))
                  (str status-kw " - original record should be linked to its retry."))
              (is (= init-submission-id (:submission original))
                  (str status-kw " - original record has incorrect submission id."))
              (is (= retry-submission-id (:submission retry))
                  (str status-kw " - retry record has incorrect submission id."))
              (is (= (get-in submission-base [init-submission-id
                                              status-kw
                                              :workflow-id])
                     (:workflow original))
                  (str status-kw " - original record has incorrect workflow id."))
              (is (nil? (:workflow retry))
                  (str status-kw " - retry record should not have workflow id "
                       "populated prior to update loop."))
              (is (every? #(= (get-in submission-base [init-submission-id
                                                       status-kw
                                                       :entity])
                              (:entity %)) [original retry])
                  (str status-kw " - original record should have "
                       "same entity as its retry.")))]
        (is (== 4 (count records))
            "Exactly 4 workflows should be visible in the database")
        (is (every? #(= snapshot-reference-id (:reference %)) records)
            "The snapshot reference ID was incorrect and should match all records")
        (compare-original-with-retry running retry-running :running)
        (compare-original-with-retry succeeded retry-succeeded :succeeded)
        (is (== (inc method-config-version-post-update) (:method_configuration_version executor-record))
            "Method configuration version was not incremented.")))))

(deftest test-terra-executor-get-retried-workflows
  (with-redefs-fn
    {#'rawls/create-or-get-snapshot-reference mock-rawls-snapshot-reference
     #'firecloud/method-configuration         mock-firecloud-get-method-configuration-init
     #'datarepo/snapshot                      mock-datarepo-snapshot
     #'firecloud/update-method-configuration  mock-firecloud-update-method-configuration-init
     #'firecloud/submit-method                (mock-firecloud-create-submission init-submission-id)
     #'firecloud/get-submission               mock-firecloud-get-submission
     #'firecloud/get-workflow                 mock-firecloud-get-known-workflow
     #'firecloud/get-workflow-outputs         mock-firecloud-get-workflow-outputs}
    #(let [source        (make-queue-from-list [[:datarepo/snapshot (mock-datarepo-snapshot)]])
           executor      (create-terra-executor (rand-int 1000000))
           workload-uuid (UUID/randomUUID)
           workload      {:uuid workload-uuid :source source :executor executor}]
       (with-redefs-fn
         {#'workloads/load-workload-for-uuid (mock-load-workload-for-uuid source executor)}
         (fn [] (executor/update-executor! workload)))
       (is (== 2 (stage/queue-length executor)))
       (jdbc/with-db-transaction [tx (postgres/wfl-db-config)]
         (jdbc/update! tx (:details executor) {:retry 2} ["id = ?" 1]))
       (is (== 2 (stage/queue-length executor))
           "The retried workflow should remain visible downstream")
       (jdbc/with-db-transaction [tx (postgres/wfl-db-config)]
         (is (== 1 (count (executor/executor-workflows tx executor {})))
             "The retried workflow should not be returned")))))

(deftest test-terra-executor-queue-length
  (with-redefs-fn
    {#'rawls/create-or-get-snapshot-reference mock-rawls-snapshot-reference
     #'firecloud/method-configuration         mock-firecloud-get-method-configuration-init
     #'datarepo/snapshot                      mock-datarepo-snapshot
     #'firecloud/update-method-configuration  mock-firecloud-update-method-configuration-init
     #'firecloud/submit-method                (mock-firecloud-create-submission init-submission-id)
     #'firecloud/get-submission               mock-firecloud-get-submission
     #'firecloud/get-workflow                 mock-firecloud-get-known-workflow
     #'firecloud/get-workflow-outputs         mock-firecloud-get-workflow-outputs}
    #(let [source                (make-queue-from-list [[:datarepo/snapshot (mock-datarepo-snapshot)]])
           executor              (create-terra-executor (rand-int 1000000))
           workload-uuid         (UUID/randomUUID)
           workload              {:uuid workload-uuid :source source :executor executor}]
       (with-redefs-fn
         {#'workloads/load-workload-for-uuid (mock-load-workload-for-uuid source executor)}
         (fn [] (executor/update-executor! workload)))
       (let [record                (#'executor/peek-terra-executor-details executor)
             succeeded-workflow-id (get-in submission-base [init-submission-id :succeeded :workflow-id])]
         (is (= succeeded-workflow-id (:workflow record))
             "Peeked record's workflow uuid should match succeeded workflow's")
         (is (= "Succeeded" (:status record))
             "Peeked record's status should match succeeded workflow's")
         (is (== 2 (stage/queue-length executor))
             "Both running and succeeded workflows in submission should be counted in queue length")
         (jdbc/with-db-transaction [tx (postgres/wfl-db-config)]
           (jdbc/update! tx (:details executor) {:status nil} ["id = ?" (:id record)]))
         (is (== 2 (stage/queue-length executor))
             "Workflows without status should be counted in queue length")
         (jdbc/with-db-transaction [tx (postgres/wfl-db-config)]
           (jdbc/update! tx (:details executor) {:workflow nil} ["id = ?" (:id record)]))
         (is (== 2 (stage/queue-length executor))
             "Workflows without status or uuid should be counted in queue length")))))

(deftest test-terra-executor-describe-method
  (let [description (executor/describe-method
                     testing-workspace
                     testing-method-configuration)]
    (is (every? description [:validWorkflow :inputs :outputs]))
    (is (= "sarscov2_illumina_full" (:name description)))))

(deftest test-terra-executor-entity-from-snapshot
  (letfn [(throw-if-called [& args]
            (throw (ex-info (str "rawls/create-snapshot-reference "
                                 "should not have been called directly")
                            {:called-with args})))]
    (with-redefs-fn
      {#'rawls/create-snapshot-reference        throw-if-called
       #'rawls/create-or-get-snapshot-reference mock-rawls-snapshot-reference}
      #(let [executor  (create-terra-executor (rand-int 1000000))
             reference (#'executor/entity-from-snapshot executor (mock-datarepo-snapshot))]
         (is (and (= snapshot-reference-id (get-in reference [:metadata :resourceId]))
                  (= snapshot-name (get-in reference [:metadata :name]))))))))

(deftest test-update-method-configuration-with-version-mismatch
  (let [id                    (rand-int 1000000)
        executor              (create-terra-executor id)
        dataReferenceNamePre  "snapshotReferenceNamePreUpdate"
        dataReferenceNamePost "snapshotReferenceNamePostUpdate"
        reference             {:metadata {:name dataReferenceNamePost}}
        inc'dMcVersion        (+ testing-method-configuration-version 1)
        rootEntityTypePre     "rootEntityTypePreUpdate"]
    (letfn [(firecloud-method-configuration
              [_workspace qualifiedMcName]
              (let [[mcNamespace mcName] (str/split qualifiedMcName #"/")]
                {:namespace           mcNamespace
                 :name                mcName
                 :dataReferenceName   dataReferenceNamePre
                 :methodConfigVersion testing-method-configuration-version
                 :rootEntityType      rootEntityTypePre}))
            (verify-firecloud-update-params
              [_workspace qualifiedMcName mc]
              (is (= qualifiedMcName (str (:namespace mc) "/" (:name mc)))
                  "Method configuration name should not change after update")
              (is (= dataReferenceNamePost (:dataReferenceName mc))
                  "Method configuration data reference name should be updated")
              (is (== inc'dMcVersion (:methodConfigVersion mc))
                  "Method configuration's actual version should be incremented")
              (is (= snapshot-table-name (:rootEntityType mc))
                  "Method configuration root entity type should be snapshot's table name"))]
      (with-redefs-fn
        {#'firecloud/method-configuration        firecloud-method-configuration
         #'datarepo/snapshot                     mock-datarepo-snapshot
         #'firecloud/update-method-configuration verify-firecloud-update-params}
        #(#'executor/update-method-configuration! executor reference))
      (let [reloaded (reload-terra-executor executor)]
        (is (nil? (:methodConfigurationVersion executor))
            "Method configuration's version in DB should initially be unpopulated")
        (is (== inc'dMcVersion (:methodConfigurationVersion reloaded))
            "Method configuration's version in DB should be updated to match actual")))))

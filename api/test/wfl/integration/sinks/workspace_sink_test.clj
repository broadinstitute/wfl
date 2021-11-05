(ns wfl.integration.sinks.workspace-sink-test
  "Test validation and operations on the Terra Workspace Sink implementation."
  (:require [clojure.test          :refer [deftest is testing use-fixtures]]
            [wfl.jdbc              :as jdbc]
            [wfl.service.firecloud :as firecloud]
            [wfl.service.postgres  :as postgres]
            [wfl.service.rawls     :as rawls]
            [wfl.sink              :as sink]
            [wfl.stage             :as stage]
            [wfl.tools.fixtures    :as fixtures]
            [wfl.tools.queues      :refer [make-queue-from-list]]
            [wfl.tools.resources   :as resources])
  (:import [clojure.lang ExceptionInfo]
           [wfl.util UserException]))

;; Workspace
(def ^:private testing-namespace "wfl-dev")
(def ^:private testing-workspace (str testing-namespace "/" "CDC_Viral_Sequencing"))

;; Entity
(def ^:private testing-entity-type "flowcell")
(def ^:private testing-entity-name "test")

(let [new-env {"WFL_FIRECLOUD_URL" "https://api.firecloud.org"
               "WFL_RAWLS_URL"     "https://rawls.dsde-prod.broadinstitute.org"}]
  (use-fixtures :once
    (fixtures/temporary-environment new-env)
    fixtures/temporary-postgresql-database))

;; Validation tests

(deftest test-validate-terra-workspace-sink-with-valid-sink-request
  (is (sink/terra-workspace-sink-validate-request-or-throw
       {:name        @#'sink/terra-workspace-sink-name
        :workspace   testing-workspace
        :entityType  "assemblies"
        :identity    "Who cares?"
        :fromOutputs {:assemblies_id "foo"}})))

(deftest test-validate-terra-workspace-sink-throws-on-invalid-sink-entity-type
  (is (thrown-with-msg?
       UserException (re-pattern sink/unknown-entity-type-error-message)
       (sink/terra-workspace-sink-validate-request-or-throw
        {:name        @#'sink/terra-workspace-sink-name
         :workspace   testing-workspace
         :entityType  "does_not_exist"
         :identity    "Who cares?"
         :fromOutputs {}}))))

(deftest test-validate-terra-workspace-sink-throws-on-malformed-fromOutputs
  (is (thrown-with-msg?
       UserException (re-pattern sink/terra-workspace-malformed-from-outputs-message)
       (sink/terra-workspace-sink-validate-request-or-throw
        {:name        @#'sink/terra-workspace-sink-name
         :workspace   testing-workspace
         :entityType  "assemblies"
         :identity    "Who cares?"
         :fromOutputs "geoff"}))))

(deftest test-validate-terra-workspace-sink-throws-on-unknown-fromOutputs-attributes
  (is (thrown-with-msg?
       UserException (re-pattern sink/unknown-attributes-error-message)
       (sink/terra-workspace-sink-validate-request-or-throw
        {:name        @#'sink/terra-workspace-sink-name
         :workspace   testing-workspace
         :entityType  "assemblies"
         :identity    "Who cares?"
         :fromOutputs {:does_not_exist "genbank_source_table"}}))))

(deftest test-validate-terra-workspace-sink-throws-on-invalid-sink-workspace
  (is (thrown-with-msg?
       UserException #"Cannot access workspace"
       (sink/terra-workspace-sink-validate-request-or-throw
        {:name        @#'sink/terra-workspace-sink-name
         :workspace   "moo/moo"
         :entityType  "moo"
         :identity    "reads_id"
         :fromOutputs {:submission_xml "submission_xml"}}))))

;; Operation tests

(deftest test-update-terra-workspace-sink
  (letfn [(sink [identifier]
            (jdbc/with-db-transaction [tx (postgres/wfl-db-config)]
              (->> {:name           "Terra Workspace"
                    :workspace      "workspace-ns/workspace-name"
                    :entityType     testing-entity-type
                    :fromOutputs    (resources/read-resource
                                     "sarscov2_illumina_full/entity-from-outputs.edn")
                    :identifier     identifier
                    :skipValidation true}
                   (sink/create-sink! tx (rand-int 1000000))
                   (zipmap [:sink_type :sink_items])
                   (sink/load-sink! tx))))
          (verify-upsert-request [workspace [[type name _]]]
            (is (= "workspace-ns/workspace-name" workspace))
            (is (= type testing-entity-type))
            (is (= name testing-entity-name)))
          (throw-if-called [fname & args]
            (throw (ex-info (str fname " should not have been called")
                            {:called-with args})))]
    (let [workflow     {:uuid    "2768b29e-c808-4bd6-a46b-6c94fd2a67aa"
                        :status  "Succeeded"
                        :outputs (-> "sarscov2_illumina_full/outputs.edn"
                                     resources/read-resource
                                     (assoc :flowcell_id testing-entity-name))}
          executor     (make-queue-from-list [[nil workflow]])
          sink-throws  (sink "not-a-workflow-input-or-output")
          sink-updates (sink "flowcell_id")]
      (testing "Sink identifier matches no workflow output or input"
        (with-redefs-fn
          {#'rawls/batch-upsert        (partial throw-if-called "rawls/batch-upsert")
           #'sink/entity-exists?       (partial throw-if-called "sink/entity-exists?")
           #'firecloud/delete-entities (partial throw-if-called "firecloud/delete-entities")}
          #(is (thrown-with-msg?
                ExceptionInfo (re-pattern sink/entity-name-not-found-error-message)
                (sink/update-sink! executor sink-throws))
               "Sink update should throw if identifier not found in workflow output or input"))
        (is (== 1 (stage/queue-length executor)) "The workflow should not have been consumed")
        (jdbc/with-db-transaction [tx (postgres/wfl-db-config)]
          (let [records (->> sink-throws :details (postgres/get-table tx))]
            (is (empty? records) "No sink records should have been written"))))
      (testing "Sink identifier matches workflow output"
        (with-redefs-fn
          {#'rawls/batch-upsert        verify-upsert-request
           #'sink/entity-exists?       (constantly false)
           #'firecloud/delete-entities (partial throw-if-called "firecloud/delete-entities")}
          #(sink/update-sink! executor sink-updates))
        (is (zero? (stage/queue-length executor)) "The workflow was not consumed")
        (jdbc/with-db-transaction [tx (postgres/wfl-db-config)]
          (let [[record & rest] (->> sink-updates :details (postgres/get-table tx))]
            (is record "The record was not written to the database")
            (is (empty? rest) "More than one record was written")
            (is (= (:uuid workflow) (:workflow record)) "The workflow UUID was not written")
            (is (= testing-entity-name (:entity record)) "The entity was not correct")))))))

(deftest test-sinking-resubmitted-workflow
  (fixtures/with-temporary-workspace
    (fn [workspace]
      (let [workflow1 {:uuid    "2768b29e-c808-4bd6-a46b-6c94fd2a67aa"
                       :status  "Succeeded"
                       :outputs {:run_id  testing-entity-name
                                 :results ["aligned-thing.cram"]}}
            workflow2 {:uuid    "2768b29e-c808-4bd6-a46b-6c94fd2a67ab"
                       :status  "Succeeded"
                       :outputs {:run_id  testing-entity-name
                                 :results ["another-aligned-thing.cram"]}}
            executor  (make-queue-from-list [[nil workflow1] [nil workflow2]])
            sink      (jdbc/with-db-transaction [tx (postgres/wfl-db-config)]
                        (->> {:name           "Terra Workspace"
                              :workspace      workspace
                              :entityType     testing-entity-type
                              :fromOutputs    {:aligned_crams "results"}
                              :identifier     "run_id"
                              :skipValidation true}
                             (sink/create-sink! tx (rand-int 1000000))
                             (zipmap [:sink_type :sink_items])
                             (sink/load-sink! tx)))]
        (sink/update-sink! executor sink)
        (is (== 1 (stage/queue-length executor))
            "one workflow should have been consumed")
        (let [{:keys [entityType name attributes]}
              (firecloud/get-entity
               workspace [testing-entity-type testing-entity-name])]
          (is (= testing-entity-type entityType))
          (is (= testing-entity-name name))
          (is (== 1 (count attributes)))
          (is (= [:aligned_crams {:itemsType "AttributeValue"
                                  :items ["aligned-thing.cram"]}]
                 (first attributes))))
        (sink/update-sink! executor sink)
        (is (zero? (stage/queue-length executor))
            "one workflow should have been consumed")
        (let [entites (firecloud/list-entities workspace testing-entity-type)]
          (is (== 1 (count entites))
              "No new entities should have been added"))
        (let [{:keys [entityType name attributes]}
              (firecloud/get-entity
               workspace [testing-entity-type testing-entity-name])]
          (is (= testing-entity-type entityType))
          (is (= testing-entity-name name))
          (is (== 1 (count attributes)))
          (is (= [:aligned_crams {:itemsType "AttributeValue"
                                  :items ["another-aligned-thing.cram"]}]
                 (first attributes))
              "attributes should have been overwritten"))))))

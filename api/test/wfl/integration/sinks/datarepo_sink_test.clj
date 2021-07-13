(ns wfl.integration.sinks.datarepo-sink-test
  "Test validation and operations on Sink stage implementations."
  (:require [clojure.test          :refer [deftest is use-fixtures]]
            [wfl.environment       :as env]
            [wfl.jdbc              :as jdbc]
            [wfl.service.datarepo  :as datarepo]
            [wfl.service.postgres  :as postgres]
            [wfl.sink              :as sink]
            [wfl.stage             :as stage]
            [wfl.tools.datasets    :as datasets]
            [wfl.tools.fixtures    :as fixtures]
            [wfl.tools.resources   :as resources]
            [wfl.tools.workloads   :refer [liftT]]
            [wfl.util              :as util])
  (:import [java.util ArrayDeque]
           [wfl.util UserException]))

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

(def ^:private ^:dynamic *dataset*)

(use-fixtures :once
  fixtures/temporary-postgresql-database
  (fixtures/method-overload-fixture
   stage/peek-queue testing-queue-type testing-queue-peek)
  (fixtures/method-overload-fixture
   stage/pop-queue! testing-queue-type testing-queue-pop)
  (fixtures/method-overload-fixture
   stage/queue-length testing-queue-type testing-queue-length)
  (fixtures/method-overload-fixture
   stage/done? testing-queue-type testing-queue-done?)
  (fixtures/bind-fixture
   *dataset*
   fixtures/with-temporary-dataset
   (datasets/unique-dataset-request
    (env/getenv "WFL_TDR_DEFAULT_PROFILE") "testing-dataset.json")))

;; Validation tests

;; Must be a function to capture value of *dataset* after setting up test
;; fixtures.
(defn ^:private datarepo-sink-request
  "Return a valid Tera DataRepo sink request."
  []
  {:name        @#'sink/datarepo-sink-name
   :dataset     *dataset*
   :table       "parameters"
   :fromOutputs {:fileref "some_output_file"}})

(deftest test-validate-datarepo-sink-with-valid-sink-request
  (is (stage/validate-or-throw (datarepo-sink-request))))

(deftest test-validate-datarepo-sink-throws-on-invalid-dataset
  (is (thrown-with-msg?
       UserException #"Cannot access dataset"
       (stage/validate-or-throw
        {:name        @#'sink/datarepo-sink-name
         :dataset     util/uuid-nil
         :table       "parameters"
         :fromOutputs {:fileref "some_output_file"}}))))

(deftest test-validate-datarepo-sink-throws-on-invalid-table
  (is (thrown-with-msg?
       UserException #"Table not found"
       (stage/validate-or-throw
        {:name        @#'sink/datarepo-sink-name
         :dataset     *dataset*
         :table       "not-a-table"
         :fromOutputs {:fileref "some_output_file"}}))))

(deftest test-validate-datarepo-sink-throws-on-malformed-fromOutputs
  (is (thrown-with-msg?
       UserException (re-pattern sink/datarepo-malformed-from-outputs-message)
       (stage/validate-or-throw
        {:name        @#'sink/datarepo-sink-name
         :dataset     *dataset*
         :table       "parameters"
         :fromOutputs "bad"}))))

(deftest test-validate-datarepo-sink-throws-on-unknown-column-name
  (is (thrown-with-msg?
       UserException (re-pattern sink/unknown-columns-error-message)
       (stage/validate-or-throw
        {:name        @#'sink/datarepo-sink-name
         :dataset     *dataset*
         :table       "parameters"
         :fromOutputs {:not-a-column "some_output_file"}}))))

;; Operation tests
(def ^:private random (partial rand-int 1000000))

(def datarepo-sink-config (comp stage/validate-or-throw datarepo-sink-request))

(deftest test-create-datarepo-sink
  (let [[type items] (liftT sink/create-sink! (random) (datarepo-sink-config))]
    (is (= @#'sink/datarepo-sink-type type))
    (is (-> items Integer/parseInt pos?))
    (let [sink (liftT sink/load-sink! {:sink_type type :sink_items items})]
      (is (= @#'sink/datarepo-sink-type (:type sink)))
      (is (every? sink [:dataset :table :fromOutputs :details]))
      (is (get-in sink [:dataset :defaultProfileId]))
      (is (liftT postgres/table-exists? (:details sink))))))

;(deftest test-update-datarepo-sink
;  (let [workflow    {:uuid    "2768b29e-c808-4bd6-a46b-6c94fd2a67aa"
;                     :status  "Succeeded"
;                     :outputs (-> "sarscov2_illumina_full/outputs.edn"
;                                resources/read-resource
;                                (assoc :flowcell_id testing-entity-name))}
;        executor    (make-queue-from-list [[nil workflow]])
;        sink        (jdbc/with-db-transaction [tx (postgres/wfl-db-config)]
;                      (->> {:name           "Terra Workspace"
;                            :workspace      "workspace-ns/workspace-name"
;                            :entityType     testing-entity-type
;                            :fromOutputs    (resources/read-resource
;                                              "sarscov2_illumina_full/entity-from-outputs.edn")
;                            :identifier     "flowcell_id"
;                            :skipValidation true}
;                        (sink/create-sink! tx (rand-int 1000000))
;                        (zipmap [:sink_type :sink_items])
;                        (sink/load-sink! tx)))]
;    (letfn [(verify-upsert-request [workspace [[type name _]]]
;              (is (= "workspace-ns/workspace-name" workspace))
;              (is (= type testing-entity-type))
;              (is (= name testing-entity-name)))
;            (throw-if-called [fname & args]
;              (throw (ex-info (str fname " should not have been called")
;                       {:called-with args})))]
;      (with-redefs-fn
;        {#'rawls/batch-upsert        verify-upsert-request
;         #'sink/entity-exists?       (constantly false)
;         #'firecloud/delete-entities (partial throw-if-called "delete-entities")}
;        #(sink/update-sink! executor sink))
;      (is (zero? (stage/queue-length executor)) "The workflow was not consumed")
;      (jdbc/with-db-transaction [tx (postgres/wfl-db-config)]
;        (let [[record & rest] (->> sink :details (postgres/get-table tx))]
;          (is record "The record was not written to the database")
;          (is (empty? rest) "More than one record was written")
;          (is (= (:uuid workflow) (:workflow record))
;            "The workflow UUID was not written")
;          (is (= testing-entity-name (:entity record))
;            "The entity was not correct"))))))
;
;(deftest test-sinking-resubmitted-workflow
;  (fixtures/with-temporary-workspace
;    (fn [workspace]
;      (let [workflow1 {:uuid    "2768b29e-c808-4bd6-a46b-6c94fd2a67aa"
;                       :status  "Succeeded"
;                       :outputs {:run_id  testing-entity-name
;                                 :results ["aligned-thing.cram"]}}
;            workflow2 {:uuid    "2768b29e-c808-4bd6-a46b-6c94fd2a67ab"
;                       :status  "Succeeded"
;                       :outputs {:run_id  testing-entity-name
;                                 :results ["another-aligned-thing.cram"]}}
;            executor  (make-queue-from-list [[nil workflow1] [nil workflow2]])
;            sink      (jdbc/with-db-transaction [tx (postgres/wfl-db-config)]
;                        (->> {:name           "Terra Workspace"
;                              :workspace      workspace
;                              :entityType     testing-entity-type
;                              :fromOutputs    {:aligned_crams "results"}
;                              :identifier     "run_id"
;                              :skipValidation true}
;                          (sink/create-sink! tx (rand-int 1000000))
;                          (zipmap [:sink_type :sink_items])
;                          (sink/load-sink! tx)))]
;        (sink/update-sink! executor sink)
;        (is (== 1 (stage/queue-length executor))
;          "one workflow should have been consumed")
;        (let [{:keys [entityType name attributes]}
;              (firecloud/get-entity workspace [testing-entity-type testing-entity-name])]
;          (is (= testing-entity-type entityType))
;          (is (= testing-entity-name name))
;          (is (== 1 (count attributes)))
;          (is (= [:aligned_crams {:itemsType "AttributeValue" :items ["aligned-thing.cram"]}]
;                (first attributes))))
;        (sink/update-sink! executor sink)
;        (is (zero? (stage/queue-length executor))
;          "one workflow should have been consumed")
;        (let [entites (firecloud/list-entities workspace testing-entity-type)]
;          (is (== 1 (count entites))
;            "No new entities should have been added"))
;        (let [{:keys [entityType name attributes]}
;              (firecloud/get-entity workspace [testing-entity-type testing-entity-name])]
;          (is (= testing-entity-type entityType))
;          (is (= testing-entity-name name))
;          (is (== 1 (count attributes)))
;          (is (= [:aligned_crams {:itemsType "AttributeValue" :items ["another-aligned-thing.cram"]}]
;                (first attributes))
;            "attributes should have been overwritten"))))))

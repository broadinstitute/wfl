(ns wfl.integration.sinks.datarepo-sink-test
  "Test validation and operations on Sink stage implementations."
  (:require [clojure.test :refer [deftest is use-fixtures]]
            [wfl.environment :as env]
            [wfl.service.postgres :as postgres]
            [wfl.sink :as sink]
            [wfl.stage :as stage]
            [wfl.tools.datasets :as datasets]
            [wfl.tools.fixtures :as fixtures]
            [wfl.tools.resources :as resources]
            [wfl.tools.workloads :refer [evalT]]
            [wfl.util :as util])
  (:import [java.util ArrayDeque UUID]
           [wfl.util UserException]))

;; Queue mocks
(def ^:private testing-queue-type "TestQueue")
(defn ^:private make-queue-from-list [items]
  {:type testing-queue-type :queue (ArrayDeque. items)})

(defn ^:private testing-queue-peek [this]
  (-> this :queue .peekFirst))

(defn ^:private testing-queue-pop [this]
  (-> this :queue .removeFirst))

(defn ^:private testing-queue-length [this]
  (-> this :queue .size))

(defn ^:private testing-queue-done? [this]
  (-> this :queue .isEmpty))

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

(def ^:private outputs
  {:outbool   true
   :outfile   "gs://broad-gotc-dev-wfl-ptc-test-inputs/external-reprocessing/exome/develop/not-a-real.unmapped.bam"
   :outfloat  (* 4 (Math/atan 1))
   :outint    27
   :outstring "Hello, World!"})

;; Must be a function to capture value of *dataset* after setting up test
;; fixtures.
(defn ^:private datarepo-sink-request
  "Return a valid Tera DataRepo sink request."
  []
  {:name        @#'sink/datarepo-sink-name
   :dataset     *dataset*
   :table       "parameters"
   :fromOutputs {:fileref "outfile"}})

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

(def ^:private datarepo-sink-config
  (comp stage/validate-or-throw datarepo-sink-request))

(deftest test-create-datarepo-sink
  (let [[type items] (evalT sink/create-sink! (random) (datarepo-sink-config))]
    (is (= @#'sink/datarepo-sink-type type))
    (is (-> items Integer/parseInt pos?))
    (let [sink (evalT sink/load-sink! {:sink_type type :sink_items items})]
      (is (= @#'sink/datarepo-sink-type (:type sink)))
      (is (every? sink [:dataset :table :fromOutputs :details]))
      (is (get-in sink [:dataset :defaultProfileId]))
      (is (evalT postgres/table-exists? (:details sink))))))

(defn ^:private create-and-load-datarepo-sink []
  (let [[type items] (evalT sink/create-sink! (random) (datarepo-sink-config))]
    (evalT sink/load-sink! {:sink_type type :sink_items items})))

(deftest test-datarepo-sink-initially-done
  (is (stage/done? (create-and-load-datarepo-sink))))

(deftest test-data-repo-job-queue-operations
  (let [sink (create-and-load-datarepo-sink)]
    (is (nil? (#'sink/peek-job-queue sink)))
    (is (thrown? AssertionError (#'sink/pop-job-queue! sink {})))))

(deftest test-datarepo-sink-to-edn
  (let [response (util/to-edn (create-and-load-datarepo-sink))]
    (is (= *dataset* (:dataset response)))
    (is (not-any? response [:id :details]))))

(defmacro throws? [exception-type]
  (let [task (gensym)]
    `(fn [~task] (is ~(list 'thrown? exception-type (list task))))))

(defn eventually [assertion task & opts]
  (let [{:keys [interval times]} (apply hash-map opts)]
    (assertion #(util/poll task (or interval 1) (or times 5)))))

(deftest test-update-datarepo-sink
  (let [description (resources/read-resource "primitive.edn")
        workflow    {:uuid (UUID/randomUUID) :outputs outputs}
        upstream    (make-queue-from-list [[description workflow]])
        sink        (create-and-load-datarepo-sink)]
    (sink/update-sink! upstream sink)
    (is (stage/done? upstream))
    (eventually (throws? UserException) #(sink/update-sink! upstream sink)
                :interval 5 :times 10)
    (is (stage/done? sink) "failed jobs are no longer considered")
    (is (or (sink/update-sink! upstream sink) true) "subsequent updates do nothing")))

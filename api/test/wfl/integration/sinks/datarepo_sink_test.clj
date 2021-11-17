(ns wfl.integration.sinks.datarepo-sink-test
  "Test validation and operations on the DataRepo sink implementation."
  (:require [clojure.test         :refer [deftest is use-fixtures]]
            [wfl.environment      :as env]
            [wfl.jdbc             :as jdbc]
            [wfl.service.postgres :as postgres]
            [wfl.sink             :as sink]
            [wfl.stage            :as stage]
            [wfl.tools.datasets   :as datasets]
            [wfl.tools.fixtures   :as fixtures]
            [wfl.tools.queues     :refer [make-queue-from-list]]
            [wfl.tools.resources  :as resources]
            [wfl.tools.workloads  :refer [evalT]]
            [wfl.util             :as util])
  (:import [java.util UUID]
           [wfl.util UserException]
           [java.util.concurrent TimeUnit]))

(def ^:private ^:dynamic *dataset*)

(use-fixtures :once
  fixtures/temporary-postgresql-database
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
  (is (sink/datarepo-sink-validate-request-or-throw (datarepo-sink-request))))

(deftest test-validate-datarepo-sink-throws-on-invalid-dataset
  (is (thrown-with-msg?
       UserException #"Cannot access dataset"
       (sink/datarepo-sink-validate-request-or-throw
        {:name        @#'sink/datarepo-sink-name
         :dataset     util/uuid-nil
         :table       "parameters"
         :fromOutputs {:fileref "some_output_file"}}))))

(deftest test-validate-datarepo-sink-throws-on-invalid-table
  (is (thrown-with-msg?
       UserException #"Table not found"
       (sink/datarepo-sink-validate-request-or-throw
        {:name        @#'sink/datarepo-sink-name
         :dataset     *dataset*
         :table       "not-a-table"
         :fromOutputs {:fileref "some_output_file"}}))))

(deftest test-validate-datarepo-sink-throws-on-malformed-fromOutputs
  (is (thrown-with-msg?
       UserException (re-pattern sink/datarepo-malformed-from-outputs-message)
       (sink/datarepo-sink-validate-request-or-throw
        {:name        @#'sink/datarepo-sink-name
         :dataset     *dataset*
         :table       "parameters"
         :fromOutputs "bad"}))))

(deftest test-validate-datarepo-sink-throws-on-unknown-column-name
  (is (thrown-with-msg?
       UserException (re-pattern sink/unknown-columns-error-message)
       (sink/datarepo-sink-validate-request-or-throw
        {:name        @#'sink/datarepo-sink-name
         :dataset     *dataset*
         :table       "parameters"
         :fromOutputs {:not-a-column "some_output_file"}}))))

;; Operation tests
(def ^:private random (partial rand-int 1000000))

(deftest test-create-datarepo-sink
  (let [[type items] (evalT sink/create-sink! (random) (datarepo-sink-request))]
    (is (= @#'sink/datarepo-sink-type type))
    (is (-> items Integer/parseInt pos?))
    (let [sink (evalT sink/load-sink! {:sink_type type :sink_items items})]
      (is (= @#'sink/datarepo-sink-type (:type sink)))
      (is (every? sink [:dataset :table :fromOutputs :details]))
      (is (get-in sink [:dataset :defaultProfileId]))
      (is (evalT postgres/table-exists? (:details sink))))))

(defn ^:private create-and-load-datarepo-sink []
  (let [[type items] (evalT sink/create-sink! (random) (datarepo-sink-request))]
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

(defn ^:private count-succeeded-ingest-jobs
  "True when all tdr ingest jobs created by the `_sink` have terminated."
  [{:keys [details] :as _sink}]
  (let [query "SELECT COUNT(*) FROM %s
               WHERE status = 'succeeded' AND consumed IS NOT NULL"]
    (jdbc/with-db-transaction [tx (postgres/wfl-db-config)]
      (postgres/throw-unless-table-exists tx details)
      (-> (jdbc/query tx (format query details)) first :count))))

(defn ^:private poll-for-results
  "Update sink until there is a truthy result or the max number of attempts is reached."
  [executor sink seconds max-attempts]
  (letfn [(update-and-check []
            (sink/update-sink! executor sink)
            (stage/done? sink))]
    (loop [attempt 1]
      (if-let [result (update-and-check)]
        result
        (if (<= max-attempts attempt)
          false
          (do (.sleep TimeUnit/SECONDS seconds)
              (recur (inc attempt))))))))

(deftest test-update-datarepo-sink
  (let [description      (resources/read-resource "primitive.edn")
        workflow         {:uuid (UUID/randomUUID) :outputs outputs}
        upstream         (make-queue-from-list [[description workflow]])
        failing-workflow {:uuid (UUID/randomUUID) :outputs {:outbool   true
                                                            :outfile   "gs://not-a-real-bucket/external-reprocessing/exome/develop/not-a-real.unmapped.bam"
                                                            :outfloat  (* 4 (Math/atan 1))
                                                            :outint    27
                                                            :outstring "Hello, World!"}}
        failing-upstream (make-queue-from-list [[description failing-workflow]])
        sink             (create-and-load-datarepo-sink)]
    (sink/update-sink! upstream sink)
    (is (stage/done? upstream))
    (is (poll-for-results upstream sink 10 20))
    (is (== 1 (count-succeeded-ingest-jobs sink)) "has a succeeded job")
    (eventually (throws? UserException) #(sink/update-sink! failing-upstream sink)
                :interval 5 :times 10)
    (is (stage/done? sink) "failed jobs are no longer considered")
    (is (or (sink/update-sink! failing-upstream sink) true) "subsequent updates do nothing")))

(ns wfl.unit.executor-test
  (:require [clojure.test         :refer [deftest is testing]]
            [wfl.executor         :as executor]
            [wfl.service.cromwell :as cromwell])
  (:import [java.util UUID]
           [wfl.util UserException]))

(deftest test-terra-executor-workflows-sql-params
  (let [executor   {:details "TerraExecutor_00000001"}
        submission (str (UUID/randomUUID))
        status     "Failed"
        filters    {:submission submission :status status}]
    (letfn [(arg-count [sql] (-> sql frequencies (get \? 0)))]
      (testing "No filters"
        (let [[sql & params] (#'executor/terra-executor-workflows-sql-params
                              executor {})]
          (is (== 0 (arg-count sql))
              "No filters should yield no query arguments")
          (is (empty? params)
              "No filters should yield no parameters")))
      (testing "Submission filter only"
        (let [[sql & params] (#'executor/terra-executor-workflows-sql-params
                              executor (select-keys filters [:submission]))]
          (is (== 1 (arg-count sql))
              "Single filter (submission) should yield 1 query argument")
          (is (= [submission] params)
              "Submission filter should yield lone submission parameter")))
      (testing "Status filter only"
        (let [[sql & params] (#'executor/terra-executor-workflows-sql-params
                              executor (select-keys filters [:status]))]
          (is (== 1 (arg-count sql))
              "Single filter (status) should yield 1 query argument")
          (is (= [status] params)
              "Status filter should yield lone status parameter")))
      (testing "Submission and status filters"
        (let [[sql & params] (#'executor/terra-executor-workflows-sql-params
                              executor (select-keys filters [:submission :status]))]
          (is (== 2 (arg-count sql))
              "Two filters (submission and status) should yield 2 query arguments")
          (is (= [submission status] params)
              "Submission and status filters should yield both parameters in order"))))))

(deftest test-retry-submission-validation-error
  (letfn [(matches-error? [maybe-error msg]
            (is (= executor/retry-invalid-submission-error-message
                   (:message maybe-error))
                (str msg)))]
    (matches-error? (#'executor/retry-submission-validation-error nil)
                    "Submission ID is a required argument")
    (matches-error? (#'executor/retry-submission-validation-error "not-a-uuid")
                    "Submission ID must be a valid UUID")
    (is (nil? (#'executor/retry-submission-validation-error (str (UUID/randomUUID))))
        "No error expected when submission ID is a valid UUID")))

(deftest test-retry-status-validation-error
  (letfn [(matches-error? [maybe-error msg]
            (is (= executor/retry-unsupported-status-error-message
                   (:message maybe-error))
                (str msg)))]
    (is (nil? (#'executor/retry-status-validation-error nil))
        "No error expected when workflow status unspecified")
    (matches-error? (#'executor/retry-status-validation-error "not-a-cromwell-status")
                    "Status must be a valid Cromwell workflow status")
    (matches-error? (#'executor/retry-status-validation-error "Running")
                    "Status must be a retriable Cromwell workflow status")
    (is (nil? (#'executor/retry-status-validation-error "Failed"))
        "No error expected for retriable Cromwell status")))

(deftest test-terra-executor-throw-if-invalid-retry-filters
  (let [workload-uuid      (str (UUID/randomUUID))
        workload           {:uuid workload-uuid}
        submission-valid   (str (UUID/randomUUID))
        submission-invalid nil
        status-valid       "Failed"
        status-invalid     "Running"]
    (letfn [(verify-submission-error
              [{:keys [validation-errors] :as _ex-data}]
              (is (some #(= executor/retry-invalid-submission-error-message %)
                        validation-errors)
                  "Expected invalid submission error message in validation errors"))
            (verify-status-error
              [{:keys [validation-errors supported-statuses] :as _ex-data}]
              (is (some #(= executor/retry-unsupported-status-error-message %)
                        validation-errors)
                  "Expected unsupported status error message in validation errors")
              (is (= cromwell/retry-status? supported-statuses)))
            (verify-filter-errors-then-throw
              [{:keys [submission status] :as filters}]
              (try
                (#'executor/terra-executor-throw-if-invalid-retry-filters
                 workload filters)
                (catch Exception cause
                  (let [data (ex-data cause)]
                    (is (= workload-uuid (:workload data)))
                    (is (= filters (:filters data)))
                    (is (= 400 (:status data)))
                    (when (= submission-invalid submission) (verify-submission-error data))
                    (when (= status-invalid status) (verify-status-error data)))
                  (throw cause))))]
      (testing "Invalid filter combination should throw UserException"
        (is (thrown-with-msg?
             UserException (re-pattern executor/terra-executor-retry-filters-invalid-error-message)
             (verify-filter-errors-then-throw {:submission submission-invalid :status status-invalid})))
        (is (thrown-with-msg?
             UserException (re-pattern executor/terra-executor-retry-filters-invalid-error-message)
             (verify-filter-errors-then-throw {:submission submission-invalid :status status-valid})))
        (is (thrown-with-msg?
             UserException (re-pattern executor/terra-executor-retry-filters-invalid-error-message)
             (verify-filter-errors-then-throw {:submission submission-valid :status status-invalid}))))
      (testing "Valid filter combination should not throw"
        (is (nil? (verify-filter-errors-then-throw {:submission submission-valid :status status-valid})))))))

(ns wfl.unit.executor-test              ; (remove-ns 'wfl.unit.executor-test)
  (:require [clojure.test         :refer [deftest is testing]]
            [wfl.executor         :as executor]
            [wfl.service.cromwell :as cromwell]
            [wfl.service.datarepo :as datarepo]
            [wfl.service.slack    :as slack])
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

(deftest test-terra-executor-table-from-snapshot-reference
  (let [executor  {}
        reference {:attributes {:snapshot (str (UUID/randomUUID))}}
        table1    "table1"
        table2    "table2"]
    (letfn [(table [table-name]
              {:name table-name})
            (datarepo-snapshot [table-names]
              (fn [_snapshot-id]
                {:tables (vec (map table table-names))}))]
      (with-redefs-fn
        {#'datarepo/snapshot (datarepo-snapshot [])}
        #(is (nil? (#'executor/table-from-snapshot-reference executor reference))
             "A snapshot with no table should log error but return nil"))
      (with-redefs-fn
        {#'datarepo/snapshot (datarepo-snapshot [table1])}
        #(is (= table1 (#'executor/table-from-snapshot-reference executor reference))
             "A snapshot with exactly 1 table should resolve to the table name"))
      (with-redefs-fn
        {#'datarepo/snapshot (datarepo-snapshot [table1 table2])}
        #(is (nil? (#'executor/table-from-snapshot-reference executor reference))
             "A snapshot with more than 1 tables should log error but return nil")))))

(deftest test-notify-on-workflow-completion
  (let [records [{:status "Running"}
                 {:status "Failed"}
                 {:status "Succeeded"}]
        workload {:executor {:workspace "workspaceNs/workspaceName"}}]
    (letfn [(mock-workflow-finished-slack-msg
              [_executor {:keys [status] :as _record}]
              (is (cromwell/final? status)
                  "Should not notify for non-final workflows"))]
      (with-redefs-fn
        {#'executor/workflow-finished-slack-msg mock-workflow-finished-slack-msg
         #'slack/notify-watchers                (constantly nil)}
        #(is (= records (#'executor/notify-on-workflow-completion workload records))
             "Should return all passed-in records")))))

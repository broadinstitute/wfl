(ns wfl.integration.modules.aou-test
  (:require [clojure.spec.alpha             :as s]
            [clojure.test                   :refer [testing is deftest use-fixtures]]
            [wfl.api.spec]
            [wfl.integration.modules.shared :as shared]
            [wfl.jdbc                       :as jdbc]
            [wfl.module.aou                 :as aou]
            [wfl.tools.fixtures             :as fixtures]
            [wfl.tools.workloads            :as workloads]
            [wfl.util                       :as util]
            [wfl.module.batch               :as batch])
  (:import [java.util UUID]))

(use-fixtures :once fixtures/temporary-postgresql-database)

(defn mock-submit-workload [& _] (UUID/randomUUID))
(defn mock-update-statuses! [tx {:keys [items] :as workload}]
  (letfn [(f [{:keys [id]}]
            (jdbc/update! tx items {:status "Succeeded"} ["id = ?" id]))]
    (run! f (workloads/workflows workload))))

(defn ^:private make-aou-workload-request []
  (-> (workloads/aou-workload-request (UUID/randomUUID))
      (assoc :creator @workloads/email)))

(defn ^:private inc-version [sample]
  (update sample :analysis_version_number inc))

(def count=1? (comp (partial == 1) count))

(deftest test-append-to-aou
  (with-redefs-fn {#'aou/submit-aou-workflow mock-submit-workload}
    #(let [workload            (workloads/execute-workload! (make-aou-workload-request))
           append-to-workload! (fn [xs] (workloads/append-to-workload! xs workload))]
       (testing "appending a sample to the workload"
         (let [response (append-to-workload! [workloads/aou-sample])]
           (is (s/valid? :wfl.api.spec/append-to-aou-response response))
           (is (count=1? response))))
       (testing "appending the same sample to the workload does nothing"
         (is (= () (append-to-workload! [workloads/aou-sample]))))
       (testing "incrementing analysis_version_number starts a new workload"
         (is (count=1? (append-to-workload! [(inc-version workloads/aou-sample)]))))
       (testing "only one workflow is started when there are multiple duplicates"
         (is (count=1?
              (append-to-workload!
               (repeat 5 (inc-version (inc-version workloads/aou-sample)))))))
       (testing "appending empty workload"
         (let [response (append-to-workload! [])]
           (is (s/valid? :wfl.api.spec/append-to-aou-response response))
           (is (empty? response)))))))

(deftest test-append-to-aou-not-started
  (with-redefs-fn {#'aou/submit-aou-workflow mock-submit-workload}
    #(let [workload (workloads/create-workload! (make-aou-workload-request))]
       (is (thrown? Exception (workloads/append-to-workload!
                               [workloads/aou-sample]
                               workload))))))

(deftest test-append-to-stopped-aou-workload
  (with-redefs-fn {#'aou/submit-aou-workflow mock-submit-workload}
    #(as-> (workloads/create-workload! (make-aou-workload-request)) workload
       (workloads/start-workload! workload)
       (workloads/stop-workload! workload)
       (is (thrown? Exception (workloads/append-to-workload!
                               [workloads/aou-sample]
                               workload))))))

(deftest test-workload-state-transition
  (shared/run-workload-state-transition-test! (make-aou-workload-request)))

(deftest test-stop-workload-state-transition
  (shared/run-stop-workload-state-transition-test! (make-aou-workload-request)))

(deftest test-retry-workflows-unsupported
  (shared/run-retry-is-not-supported-test! (make-aou-workload-request)))

(deftest test-aou-workload-not-finished-until-stopped
  (with-redefs-fn {#'aou/submit-aou-workflow         mock-submit-workload
                   #'batch/update-workflow-statuses! mock-update-statuses!}
    #(let [workload (-> (make-aou-workload-request)
                        (workloads/execute-workload!)
                        (workloads/update-workload!))]
       (is (not (:finished workload)))
       (workloads/append-to-workload! [workloads/aou-sample] workload)
       (let [workload (workloads/load-workload-for-uuid (:uuid workload))]
         (is (every? (comp #{"Submitted"} :status) (workloads/workflows workload)))
         (let [workload (workloads/update-workload! workload)]
           (is (every? (comp #{"Succeeded"} :status) (workloads/workflows workload)))
           (is (not (:finished workload))))))))

;; rr: GH-1071
(deftest test-exec-on-same-workload-request
  (testing "executing a workload-request twice should not create a new workload"
    (let [request (make-aou-workload-request)]
      (is (= (workloads/execute-workload! request)
             (workloads/execute-workload! request))))))

(deftest test-exec-on-similar-workload-request
  (testing "output bucket slashes should be standardized to not create new workloads unnecessarily"
    (let [request (make-aou-workload-request)
          slashified (update request :output util/slashify)
          deslashified (update request :output util/de-slashify)]
      (is (not (= slashified deslashified)))
      (is (= (workloads/execute-workload! slashified)
             (workloads/execute-workload! deslashified))))))

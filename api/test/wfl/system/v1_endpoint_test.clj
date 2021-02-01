(ns wfl.system.v1-endpoint-test
  (:require [clojure.set                :as set]
            [clojure.string             :as str]
            [clojure.test               :refer [deftest testing is]]
            [wfl.service.cromwell       :as cromwell]
            [wfl.service.google.storage :as gcs]
            [wfl.tools.endpoints        :as endpoints]
            [wfl.tools.fixtures         :refer [gcs-test-bucket
                                                temporary-postgresql-database
                                                with-temporary-cloud-storage-folder]]
            [wfl.tools.workloads        :as workloads]
            [wfl.util                   :as util])
  (:import (clojure.lang ExceptionInfo)
           (java.util UUID)))

(defn make-create-workload [make-request]
  (fn [] (endpoints/create-workload (make-request (UUID/randomUUID)))))

(def create-aou-workload    (make-create-workload workloads/aou-workload-request))
(def create-arrays-workload (make-create-workload workloads/arrays-workload-request))
(def create-sg-workload     (make-create-workload workloads/sg-workload-request))
(def create-wgs-workload    (make-create-workload workloads/wgs-workload-request))
(def create-xx-workload     (make-create-workload workloads/xx-workload-request))

(defn create-copyfile-workload [src dst]
  (endpoints/create-workload (workloads/copyfile-workload-request src dst)))

(defn ^:private verify-succeeded-workflow
  [{:keys [inputs status updated uuid] :as _workflow}]
  (is (map? inputs) "Every workflow should have nested inputs")
  (is updated)
  (is uuid)
  (is (= "Succeeded" status)))

(defn ^:private verify-succeeded-workload [workload]
  (run! verify-succeeded-workflow (:workflows workload)))

(defn ^:private verify-internal-properties-removed [workload]
  (letfn [(go! [key]
            (is (util/absent? workload key)
                (format "workload should not contain %s" key))
            (is (every? #(util/absent? % key) (:workflows workload))
                (format "workflows should not contain %s" key)))]
    (run! go! [:id :items])))

(deftest test-oauth2-endpoint
  (testing "The `oauth2_id` endpoint indeed provides an ID"
    (let [response (endpoints/get-oauth2-id)]
      (is (= (count response) 2))
      (is (some #(= % :oauth2-client-id) response))
      (is (some #(str/includes? % "apps.googleusercontent.com") response)))))

(defn ^:private test-create-workload
  [request]
  (letfn [(test! [{:keys [pipeline] :as request}]
            (testing (format "calling api/v1/create with %s workload request"
                             pipeline)
              (let [{:keys [created creator executor started uuid] :as workload}
                    (endpoints/create-workload request)]
                (is uuid "workloads should be been assigned a uuid")
                (is created "should have a created timestamp")
                (is (= (:email @endpoints/userinfo) creator)
                    "creator inferred from auth token")
                (is (not started) "hasn't been started in cromwell")
                (letfn [(included [m] (select-keys m [:pipeline :project]))]
                  (is (= (included request) (included workload))))
                (is (= executor (or (:executor request) (:cromwell request)))
                    "lost track of executor/cromwell")
                (verify-internal-properties-removed workload))))]
    (test! request)
    (testing "passed :cromwell rather than :executor"
      (test! (set/rename-keys request {:executor :cromwell})))))

(deftest test-create-wgs-workload
  (test-create-workload (workloads/wgs-workload-request (UUID/randomUUID))))
(deftest test-create-aou-workload
  (test-create-workload (workloads/aou-workload-request (UUID/randomUUID))))
(deftest test-create-arrays-workload
  (test-create-workload (workloads/arrays-workload-request (UUID/randomUUID))))
(deftest test-create-xx-workload
  (test-create-workload (workloads/xx-workload-request (UUID/randomUUID))))
(deftest test-create-sg-workload
  (test-create-workload (workloads/sg-workload-request (UUID/randomUUID))))
(deftest test-create-copyfile-workload
  (test-create-workload (workloads/copyfile-workload-request
                         "gs://fake-inputs/lolcats.txt"
                         "gs://fake-outputs/copied.txt")))

(defn ^:private test-start-workload
  [{:keys [uuid pipeline] :as workload}]
  (testing (format "calling api/v1/start with %s workload" pipeline)
    (let [workload (endpoints/start-workload workload)]
      (is (= uuid (:uuid workload)))
      (is (:started workload))
      (let [{:keys [workflows]} workload]
        (is (every? :updated workflows))
        (is (every? :uuid workflows)))
      (verify-internal-properties-removed workload)
      (workloads/when-done verify-succeeded-workload workload))))

(deftest ^:parallel test-start-wgs-workload
  (test-start-workload (create-wgs-workload)))
(deftest ^:parallel test-start-aou-workload
  (test-start-workload (create-aou-workload)))
(deftest ^:parallel test-start-arrays-workload
  (test-start-workload (create-arrays-workload)))
(deftest ^:parallel test-start-xx-workload
  (test-start-workload (create-xx-workload)))
(deftest ^:parallel test-start-sg-workload
  (test-start-workload (create-sg-workload)))
(deftest ^:parallel test-start-copyfile-workload
  (with-temporary-cloud-storage-folder gcs-test-bucket
    (fn [url]
      (let [src (str url "input.txt")
            dst (str url "output.txt")]
        (-> (str/join "/" ["test" "resources" "copy-me.txt"])
            (gcs/upload-file src))
        (test-start-workload (create-copyfile-workload src dst))))))

(defn ^:private test-exec-workload
  [{:keys [pipeline] :as request}]
  (testing (format "calling api/v1/exec with %s workload request" pipeline)
    (let [{:keys [created creator started uuid workflows] :as workload}
          (endpoints/exec-workload request)]
      (is uuid    "workloads should have a uuid")
      (is created "should have a created timestamp")
      (is started "should have a started timestamp")
      (is (= (:email @endpoints/userinfo) creator)
          "creator inferred from auth token")
      (letfn [(included [m] (select-keys m [:executor :pipeline :project]))]
        (is (= (included request) (included workload))))
      (is (every? :updated workflows))
      (is (every? :uuid workflows))
      (verify-internal-properties-removed workload)
      (workloads/when-done verify-succeeded-workload workload))))

(deftest ^:parallel test-exec-wgs-workload
  (test-exec-workload (workloads/wgs-workload-request (UUID/randomUUID))))
(deftest ^:parallel test-exec-wgs-workload-specifying-cromwell
  ;; All modules make use of the same code/behavior here, no need to spam Cromwell
  (test-exec-workload (-> (workloads/wgs-workload-request (UUID/randomUUID))
                          (set/rename-keys {:executor :cromwell}))))
(deftest ^:parallel test-exec-aou-workload
  (test-exec-workload (workloads/aou-workload-request (UUID/randomUUID))))
(deftest ^:parallel test-exec-arrays-workload
  (test-exec-workload (workloads/arrays-workload-request (UUID/randomUUID))))
(deftest ^:parallel test-exec-xx-workload
  (test-exec-workload (workloads/xx-workload-request (UUID/randomUUID))))
(deftest ^:parallel test-exec-sg-workload
  (test-exec-workload (workloads/sg-workload-request (UUID/randomUUID))))

(comment
  (clojure.test/test-vars [#'test-exec-sg-workload]))

(deftest ^:parallel test-exec-copyfile-workload
  (with-temporary-cloud-storage-folder gcs-test-bucket
    (fn [url]
      (let [src (str url "input.txt")
            dst (str url "output.txt")]
        (-> (str/join "/" ["test" "resources" "copy-me.txt"])
            (gcs/upload-file src))
        (test-exec-workload (workloads/copyfile-workload-request src dst))))))

(deftest ^:parallel test-append-to-aou-workload
  (let [await    (partial cromwell/wait-for-workflow-complete :aou-dev)
        workload (endpoints/exec-workload
                  (workloads/aou-workload-request (UUID/randomUUID)))]
    (testing "appending sample successfully launches an aou workflow"
      (is (->> workload
               (endpoints/append-to-aou-workload [workloads/aou-sample])
               (map (comp await :uuid))
               (every? #{"Succeeded"})))
      (->> (endpoints/get-workload-status (:uuid workload))
           (workloads/when-done verify-succeeded-workload)))))

(deftest test-bad-pipeline
  (let [request (-> (workloads/copyfile-workload-request
                     "gs://fake/in" "gs://fake/out")
                    (assoc :pipeline "geoff"))]
    (testing "create-workload! fails (400) with bad request"
      (is (thrown-with-msg? ExceptionInfo #"clj-http: status 400"
                            (endpoints/create-workload request))))
    (testing "exec-workload! fails (400) with bad request"
      (is (thrown-with-msg? ExceptionInfo #"clj-http: status 400"
                            (endpoints/exec-workload request))))))

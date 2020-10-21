(ns wfl.system.v1-endpoint-test
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest testing is]]
            [wfl.service.gcs :as gcs]
            [wfl.tools.endpoints :as endpoints]
            [wfl.tools.fixtures :refer [with-temporary-gcs-folder clean-db-fixture]]
            [wfl.tools.workloads :as workloads]
            [wfl.util :as util]
            [wfl.service.cromwell :as cromwell])
  (:import (java.util UUID)
           (clojure.lang ExceptionInfo)))

(defn make-create-workload [make-request]
  (fn [] (endpoints/create-workload (make-request (UUID/randomUUID)))))

(def create-wgs-workload (make-create-workload workloads/wgs-workload-request))
(def create-aou-workload (make-create-workload workloads/aou-workload-request))
(def create-xx-workload (make-create-workload workloads/xx-workload-request))
(defn create-copyfile-workload [src dst]
  (endpoints/create-workload (workloads/copyfile-workload-request src dst)))

(defn- get-existing-workload-uuids []
  (->> (endpoints/get-workloads) (map :uuid) set))

(defn- verify-succeeded-workflow [workflow]
  (is (map? (:inputs workflow)) "Every workflow should have nested inputs")
  (is (:updated workflow))
  (is (:uuid workflow))
  (is (= "Succeeded" (:status workflow))))

(defn- verify-succeeded-workload [workload]
  (run! verify-succeeded-workflow (:workflows workload)))

(deftest test-oauth2-endpoint
  (testing "The `oauth2_id` endpoint indeed provides an ID"
    (let [response (endpoints/get-oauth2-id)]
      (is (= (count response) 2))
      (is (some #(= % :oauth2-client-id) response))
      (is (some #(str/includes? % "apps.googleusercontent.com") response)))))

(deftest test-create-workload
  (letfn [(go! [uuids {:keys [pipeline] :as request}]
            (testing (format "calling api/v1/create with %s workload request" pipeline)
              (let [{:keys [uuid] :as workload} (endpoints/create-workload request)]
                (is uuid "workloads should be been assigned a uuid")
                (is (util/absent? uuids uuid))
                (is (:created workload) "should have a created timestamp")
                (is (= (:email @endpoints/userinfo) (:creator workload)) "creator inferred from auth token")
                (is (not (:started workload)) "hasn't been started in cromwell")
                (let [include [:pipeline :cromwell :project]]
                  (is (= (select-keys request include) (select-keys workload include))))
                (conj uuids uuid))))]
    (reduce go!
      (get-existing-workload-uuids)
      [(workloads/wgs-workload-request (UUID/randomUUID))
       (workloads/aou-workload-request (UUID/randomUUID))
       (workloads/xx-workload-request (UUID/randomUUID))
       (workloads/copyfile-workload-request
         "gs://fake-inputs/lolcats.txt"
         "gs://fake-outputs/copied.txt")])))

(deftest test-start-workload
  (letfn [(go! [{:keys [uuid pipeline] :as workload}]
            (testing (format "calling api/v1/start with %s workload" pipeline)
              (let [workload (endpoints/start-workload workload)]
                (is (= uuid (:uuid workload)))
                (is (:started workload))
                (let [{:keys [workflows]} workload]
                  (is (every? :updated workflows))
                  (is (every? :uuid workflows)))
                (workloads/when-done verify-succeeded-workload workload))))]
    (with-temporary-gcs-folder uri
      (let [src (str uri "input.txt")]
        (->
          (str/join "/" ["test" "resources" "copy-me.txt"])
          (gcs/upload-file src))
        (run! go!
          [(create-wgs-workload)
           (create-aou-workload)
           (create-xx-workload)
           (create-copyfile-workload src (str uri "output.txt"))])))))

(deftest test-exec-workload
  (letfn [(go! [uuids {:keys [pipeline] :as request}]
            (testing (format "calling api/v1/exec with %s workload request" pipeline)
              (let [{:keys [uuid] :as workload} (endpoints/exec-workload request)]
                (is uuid "workloads should be been assigned a uuid")
                (is (util/absent? uuids uuid) "must have a new unique id")
                (is (:created workload) "should have a created timestamp")
                (is (:started workload) "should have a started timestamp")
                (is (= (:email @endpoints/userinfo) (:creator workload)) "creator inferred from auth token")
                (let [include [:pipeline :cromwell :project]]
                  (is (= (select-keys request include) (select-keys workload include))))
                (let [{:keys [workflows]} workload]
                  (is (every? :updated workflows))
                  (is (every? :uuid workflows)))
                (workloads/when-done verify-succeeded-workload workload)
                (conj uuids uuid))))]
    (with-temporary-gcs-folder uri
      (let [src (str uri "input.txt")]
        (-> (str/join "/" ["test" "resources" "copy-me.txt"])
          (gcs/upload-file src))
        (reduce go!
          (get-existing-workload-uuids)
          [(workloads/wgs-workload-request (UUID/randomUUID))
           (workloads/aou-workload-request (UUID/randomUUID))
           (workloads/xx-workload-request (UUID/randomUUID))
           (workloads/copyfile-workload-request src (str uri "output.txt"))])))))

(deftest test-append-to-aou-workload
  (let [await    (partial cromwell/wait-for-workflow-complete :aou-dev)
        workload (endpoints/exec-workload
                   (workloads/aou-workload-request (UUID/randomUUID)))]
    (testing "appending sample successfully launches an aou workflow"
      (is
        (every? #{"Succeeded"}
          (map (comp await :uuid)
            (endpoints/append-to-aou-workload [workloads/aou-sample] workload)))))
    (testing "same sample does not start a new workflow"
      (is
        (empty?
          (endpoints/append-to-aou-workload [workloads/aou-sample] workload))))
    (testing "bumping version number starts a new workflow"
      (is (== 1
            (count
              (endpoints/append-to-aou-workload
                [(assoc workloads/aou-sample :analysis_version_number 2)]
                workload)))))
    (testing "appending an empty list of samples fails"
      (is (thrown? Exception (endpoints/append-to-aou-workload [] workload))))))

(deftest test-bad-pipeline
  (let [request
        (-> (workloads/copyfile-workload-request "gs://fake/in" "gs://fake/out")
          (assoc :pipeline "geoff"))]
    (testing "create-workload! fails with bad request"
      (is (thrown? ExceptionInfo (endpoints/create-workload request))))
    (testing "create-workload! fails with bad request"
      (is (thrown? ExceptionInfo (endpoints/exec-workload request))))))

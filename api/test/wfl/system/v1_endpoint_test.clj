(ns wfl.system.v1-endpoint-test
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest testing is]]
            [wfl.service.gcs :as gcs]
            [wfl.tools.endpoints :as endpoints]
            [wfl.tools.fixtures :refer [with-temporary-gcs-folder temporary-postgresql-database]]
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

(defn test-oauth2-endpoint []
  (testing "The `oauth2_id` endpoint indeed provides an ID"
    (let [response (endpoints/get-oauth2-id)]
      (is (= (count response) 2))
      (is (some #(= % :oauth2-client-id) response))
      (is (some #(str/includes? % "apps.googleusercontent.com") response)))))

(defn test-create-workload [r]
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
    (go! (get-existing-workload-uuids) r)))

(defn test-start-workload [w]
  (letfn [(go! [{:keys [uuid pipeline] :as workload}]
            (testing (format "calling api/v1/start with %s workload" pipeline)
              (let [workload (endpoints/start-workload workload)]
                (is (= uuid (:uuid workload)))
                (is (:started workload))
                (let [{:keys [workflows]} workload]
                  (is (every? :updated workflows))
                  (is (every? :uuid workflows)))
                (workloads/when-done verify-succeeded-workload workload))))]
    (go! w)))

(defn test-exec-workload [r]
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
    (go! (get-existing-workload-uuids) r)))

(defn test-append-to-aou-workload []
  (let [await    (partial cromwell/wait-for-workflow-complete :aou-dev)
        workload (endpoints/exec-workload
                   (workloads/aou-workload-request (UUID/randomUUID)))]
    (testing "appending sample successfully launches an aou workflow"
      (is
        (every? #{"Succeeded"}
          (map (comp await :uuid)
            (endpoints/append-to-aou-workload [workloads/aou-sample] workload))))
      (->> (endpoints/get-workload-status (:uuid workload))
        (workloads/when-done verify-succeeded-workload)))))

(defn test-bad-pipeline []
  (let [request (-> (workloads/copyfile-workload-request "gs://fake/in" "gs://fake/out")
                  (assoc :pipeline "geoff"))]
    (testing "create-workload! fails with bad request"
      (is (thrown? ExceptionInfo (endpoints/create-workload request))))
    (testing "create-workload! fails with bad request"
      (is (thrown? ExceptionInfo (endpoints/exec-workload request))))))


(deftest parallel-tests
  (with-temporary-gcs-folder uri
    (let [src (str uri "input.txt")
          _ (-> (str/join "/" ["test" "resources" "copy-me.txt"])
                (gcs/upload-file src))
          requests (fn [] [(workloads/wgs-workload-request (UUID/randomUUID))
                           (workloads/aou-workload-request (UUID/randomUUID))
                           (workloads/xx-workload-request (UUID/randomUUID))
                           (workloads/copyfile-workload-request src (str uri "output.txt"))])
          workloads (fn [] [(create-wgs-workload)
                            (create-aou-workload)
                            (create-xx-workload)
                            (create-copyfile-workload src (str uri "output.txt"))])
          test-fns (concat
                     (map #(partial test-exec-workload %) requests)
                     [test-oauth2-endpoint
                      test-append-to-aou-workload
                      test-bad-pipeline])
          futures (doall (map future-call test-fns))]
      (run! deref futures))))


(deftest parallel-test-different
  (with-temporary-gcs-folder uri
    (let [create-requests [(workloads/wgs-workload-request (UUID/randomUUID))
                           (workloads/aou-workload-request (UUID/randomUUID))
                           (workloads/xx-workload-request (UUID/randomUUID))]
          create-futures (doall (map #(future (endpoints/create-workload %))
                                     create-requests))
          create-responses (map deref create-futures)]
      (letfn [(test-create-response [[{:keys [pipeline] :as request} {:keys [uuid] :as workload}]]
                     (testing (format "calling api/v1/create with %s workload request" pipeline)
                         (is uuid "workloads should be been assigned a uuid")
                         (is (:created workload) "should have a created timestamp")
                         (is (= (:email @endpoints/userinfo) (:creator workload)) "creator inferred from auth token")
                         (is (not (:started workload)) "hasn't been started in cromwell")
                         (let [include [:pipeline :cromwell :project]]
                           (is (= (select-keys request include) (select-keys workload include))))))]
        (run! test-create-response (map list create-requests create-responses))))))

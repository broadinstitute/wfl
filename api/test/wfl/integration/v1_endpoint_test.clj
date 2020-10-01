(ns wfl.integration.v1-endpoint-test
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest testing is] :as clj-test]
            [wfl.tools.endpoints :as endpoints]
            [wfl.tools.fixtures :refer [with-temporary-gcs-folder clean-db-fixture]]
            [wfl.tools.workloads :as workloads])
  (:import (java.util UUID)))

;; Here we register db-fixture to be called once, wrapping ALL tests in this namespace
;; (clj-test/use-fixtures :once clean-db-fixture)

(def make-wgs-workload (partial endpoints/create-workload (workloads/wgs-workload (UUID/randomUUID))))
(defn make-aou-workload [] ((partial endpoints/create-workload (workloads/aou-workload (UUID/randomUUID)))))

(def get-existing-workload-uuids
  (comp set (partial map :uuid) endpoints/get-workloads))

(deftest test-oauth2-endpoint
  (testing "The `oauth2_id` endpoint indeed provides an ID"
    (let [response (endpoints/get-oauth2-id)]
      (is (= (count response) 2))
      (is (some #(= % :oauth2-client-id) response))
      (is (some #(str/includes? % "apps.googleusercontent.com") response)))))

(deftest test-create-wgs-workload
  (testing "The `create` endpoint creates new WGS workload"
    (let [uuids-before        (get-existing-workload-uuids)
          wl                  (workloads/wgs-workload (UUID/randomUUID))
          no-items-output     (dissoc wl :items :output)
          {:keys [id items pipeline uuid] :as response} (make-wgs-workload)]
      (is uuid "Workloads should have been assigned a uuid")
      (is (not (contains? uuids-before uuid)) "The new workload uuid was not unique")
      (is (not (:started response)) "The workload should not have been started")
      (let [got (endpoints/get-workload-status uuid)]
        (is (= no-items-output (select-keys response (keys no-items-output))))
        (is (str/starts-with? items pipeline))
        (is (str/ends-with? items (str id)))
        (is (= (select-keys got (keys response)) response))
        (is (:workflows got))))))

(deftest test-create-aou-workload
  (testing "The `create` endpoint creates new AOU workload"
    (let [uuids-before     (get-existing-workload-uuids)
          wl               (workloads/aou-workload (UUID/randomUUID))
          no-items-project (dissoc wl :items :project)
          {:keys [items uuid] :as response} (make-aou-workload)]
      (is uuid "Workloads should have been assigned a uuid")
      (is (not (contains? uuids-before uuid)) "The new workload uuid was not unique")
      (is (not (:started response)) "The workload should not have been started")
      (is (string? items) "items of the created aou workload should be a string")
      (is (= no-items-project (select-keys response (keys no-items-project)))))))

(deftest test-start-aou-workload
  (testing "The `start` endpoint starts an existing AOU workload"
    (let [unstarted (make-aou-workload)
          response  (endpoints/start-workload unstarted)
          status    (endpoints/get-workload-status (:uuid response))]
      (is (:uuid unstarted) "Workload should have been assigned a uuid")
      (is (= (:uuid response) (:uuid unstarted)) "uuids are not modified by start")
      (is (:started response) "The workload should have a started time stamp")
      (is (nil? (seq (:workflows status))) "The workload should not have any workflows yet"))))

(deftest test-exec-wgs-workload
  (testing "The `exec` endpoint creates and starts a WGS workload"
    (let [uuids-before (get-existing-workload-uuids)
          {:keys [uuid started]} (endpoints/exec-workload (workloads/wgs-workload (UUID/randomUUID)))]
      (is (not (contains? uuids-before uuid)) "The new workload uuid was not unique")
      (is started "The workload wasn't started"))))

(deftest test-exec-aou-workload
  (testing "The `exec` endpoint creates and starts an AOU workload"
    (let [uuids-before (get-existing-workload-uuids)
          {:keys [uuid started]} (endpoints/exec-workload (workloads/aou-workload (UUID/randomUUID)))]
      (is (not (contains? uuids-before uuid)) "The new workload uuid was not unique")
      (is started "The workload wasn't started"))))


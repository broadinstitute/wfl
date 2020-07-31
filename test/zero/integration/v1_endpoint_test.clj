(ns zero.integration.v1-endpoint-test
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest testing is] :as clj-test]
            [zero.module.copyfile :as cp]
            [zero.service.cromwell :refer [wait-for-workflow-complete]]
            [zero.service.gcs :as gcs]
            [zero.tools.endpoints :as endpoints]
            [zero.tools.fixtures :refer [with-temporary-gcs-folder clean-db-fixture]]
            [zero.tools.workloads :as workloads])
  (:import (java.util UUID)))

;; Here we register db-fixture to be called once, wrapping ALL tests in this namespace
;; (clj-test/use-fixtures :once clean-db-fixture)

(def make-wgs-workload (partial endpoints/create-workload workloads/wgs-workload))
(defn make-aou-workload [] ((partial endpoints/create-workload (workloads/aou-workload (UUID/randomUUID)))))

(def get-existing-workload-uuids
  (comp set (partial map :uuid) endpoints/get-workloads))

(deftest test-create-wgs-workload
  (testing "The `create` endpoint creates new WGS workload"
    (let [uuids-before (get-existing-workload-uuids)
          no-items     (dissoc workloads/wgs-workload :items)
          {:keys [id items pipeline uuid] :as response} (make-wgs-workload)]
      (is uuid "Workloads should have been assigned a uuid")
      (is (not (contains? uuids-before uuid)) "The new workload uuid was not unique")
      (is (not (:started response)) "The workload should not have been started")
      (let [got (endpoints/get-workload-status uuid)]
        (is (= no-items (select-keys response (keys no-items))))
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

(deftest test-start-wgs-workload
  (testing "The `start` endpoint starts an existing WGS workload"
    (let [unstarted (make-wgs-workload)
          response  (endpoints/start-workload unstarted)
          status    (endpoints/get-workload-status (:uuid response))]
      (is (:uuid unstarted) "Workload should have been assigned a uuid")
      (is (= (:uuid response) (:uuid unstarted)) "uuids are not modified by start")
      (is (:started response) "The workload should have a started time stamp")
      (is (every? :status (:workflows status))))))

(deftest test-start-aou-workload
  (testing "The `start` endpoint starts an existing AOU workload"
    (let [unstarted (make-aou-workload)
          response  (endpoints/start-workload unstarted)
          status    (endpoints/get-workload-status (:uuid response))]
      (is (:uuid unstarted) "Workload should have been assigned a uuid")
      (is (= (:uuid response) (:uuid unstarted)) "uuids are not modified by start")
      (is (:started response) "The workload should have a started time stamp")
      (is (nil? (seq (:workflows status))) "The workload should not have any workflows yet"))))

(deftest test-start-wgs-workflow
  (testing "The `wgs` endpoint starts a new workflow."
    (let [env      :wgs-dev
          await     (partial wait-for-workflow-complete env)
          workflow {:environment env
                    :max         1
                    :input_path  "gs://broad-gotc-test-storage/single_sample/plumbing/bams/2m"
                    :output_path (str "gs://broad-gotc-dev-zero-test/wgs-test-output/" (UUID/randomUUID))}
          ids      (:results (endpoints/start-wgs-workflow workflow))
          results  (zipmap ids (map await ids))]
      (is (every? #{"Succeeded"} (vals results))))))

(deftest test-append-to-aou-workload
  (testing "The `append_to_aou` endpoint appends a new workflow to aou workload."
    (let [env          :aou-dev
          unstarted    (make-aou-workload)
          started      (endpoints/start-workload unstarted)
          started-uuid (:uuid started)
          await        (partial wait-for-workflow-complete env)
          samples      (assoc workloads/aou-sample :uuid started-uuid)
          ids          (:results (endpoints/append-to-aou-workload samples))
          results      (zipmap ids (map await ids))]
      (is (every? #{"Succeeded"} (vals results))))))

(deftest test-exec-wgs-workload
  (testing "The `exec` endpoint creates and starts a WGS workload"
    (let [uuids-before (get-existing-workload-uuids)
          {:keys [uuid started]} (endpoints/exec-workload workloads/wgs-workload)]
      (is (not (contains? uuids-before uuid)) "The new workload uuid was not unique")
      (is started "The workload wasn't started"))))

(deftest test-exec-aou-workload
  (testing "The `exec` endpoint creates and starts an AOU workload"
    (let [uuids-before (get-existing-workload-uuids)
          {:keys [uuid started]} (endpoints/exec-workload (workloads/aou-workload (UUID/randomUUID)))]
      (is (not (contains? uuids-before uuid)) "The new workload uuid was not unique")
      (is started "The workload wasn't started"))))

(deftest test-copyfile-workload
  (with-temporary-gcs-folder uri
    (let [src (str/join [uri "input.txt"])
          dst (str/join [uri "output.txt"])]
      (->
       (str/join "/" ["test" "zero" "resources" "copy-me.txt"])
       (gcs/upload-file src))
      (let [workload  (workloads/make-copyfile-workload src dst)
            await     (comp (partial wait-for-workflow-complete :gotc-dev) :uuid)
            submitted (endpoints/exec-workload workload)]
        (is (= (:pipeline submitted) cp/pipeline))
        (is (:started submitted))
        (let [result (-> submitted :workflows first await)]
          (is (= "Succeeded" result))
          (is (gcs/object-meta dst) "The file was not copied!"))))))

(ns zero.integration.v1-endpoint-test
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest testing is]]
            [zero.module.copyfile :as cp]
            [zero.service.cromwell :refer [wait-for-workflow-complete]]
            [zero.service.gcs :as gcs]
            [zero.tools.endpoints :as endpoints]
            [zero.tools.fixtures :refer [with-temporary-gcs-folder]]
            [zero.tools.workloads :as workloads])
  (:import (java.util UUID)))

(def make-workload (partial endpoints/create-workload workloads/wl-workload))

(def get-existing-workload-uuids
  (comp set (partial map :uuid) endpoints/get-workloads))

(deftest test-create-workload
  (testing "The `create` endpoint creates new workload"
    (let [uuids-before (get-existing-workload-uuids)
          no-items     (dissoc workloads/wl-workload :items)
          {:keys [id items pipeline uuid] :as response} (make-workload)]
      (is uuid "Workloads should have been assigned a uuid")
      (is (not (contains? uuids-before uuid)) "The new workflow uuid was not unique")
      (is (not (:started response)) "The workload should not have been started")
      (let [got (endpoints/get-workload-status uuid)]
        (is (= no-items (select-keys response (keys no-items))))
        (is (str/starts-with? items pipeline))
        (is (str/ends-with? items (str id)))
        (is (= (select-keys got (keys response)) response))
        (is (:workflows got))))))

(deftest test-start-workload
  (testing "The `start` endpoint starts an existing workload"
    (let [unstarted (or (endpoints/first-pending-workload) make-workload)
          response  (endpoints/start-workload unstarted)
          status    (endpoints/get-workload-status (:uuid response))]
      (is (:uuid unstarted) "Workloads should have been assigned a uuid")
      (is (= (:uuid response) (:uuid unstarted)) "uuids are not modified by start")
      (is (:started response) "The workload should have a started time stamp")
      (is (every? :status (:workflows status))))))

(deftest test-start-wgs-workflow
  (testing "The `wgs` endpoint starts a new workflow."
    (let [env      :wgs-dev
          await     (partial wait-for-workflow-complete env)
          workflow {:environment env
                    :max         1
                    :input_path  "gs://broad-gotc-test-storage/single_sample/plumbing/bams/2m"
                    :output_path (str "gs://broad-gotc-dev-zero-test/wgs-test-output/" (UUID/randomUUID))}
          ids      (:results (endpoints/start-wgs-workflow workflow))
          results  (zipmap ids (map (comp :status await) ids))]
      (is (every? #{"Succeeded"} (vals results))))))

(deftest test-exec-workload
  (testing "The `exec` endpoint creates and starts a workload"
    (let [uuids-before (get-existing-workload-uuids)
          {:keys [uuid started]} (endpoints/exec-workload workloads/wl-workload)]
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
          (is (= "Succeeded" (:status result)))
          (is (gcs/object-meta dst) "The file was not copied!"))))))

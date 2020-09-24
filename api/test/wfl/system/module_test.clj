(ns wfl.system.module-test
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest testing is] :as clj-test]
            [wfl.module.copyfile :as cp]
            [wfl.service.cromwell :refer [wait-for-workflow-complete]]
            [wfl.service.gcs :as gcs]
            [wfl.tools.endpoints :as endpoints]
            [wfl.tools.fixtures :refer [with-temporary-gcs-folder]]
            [wfl.tools.workloads :as workloads]
            [wfl.util :as util])
  (:import (java.util UUID)))

(def make-wgs-workload (partial endpoints/create-workload (workloads/wgs-workload (UUID/randomUUID))))
(defn make-aou-workload [] ((partial endpoints/create-workload (workloads/aou-workload (UUID/randomUUID)))))

(deftest test-start-wgs-workload
  (testing "The `start` endpoint starts an existing WGS workload"
    (let [unstarted (make-wgs-workload)
          response  (endpoints/start-workload unstarted)
          status    (endpoints/get-workload-status (:uuid response))]
      (is (:uuid unstarted) "Workload should have been assigned a uuid")
      (is (= (:uuid response) (:uuid unstarted)) "uuids are not modified by start")
      (is (:started response) "The workload should have a started time stamp")
      (is (every? :status (:workflows status)) "Not all of the workflows have been started")
      (let [env             :wgs-dev
            ids             (map :uuid (:workflows status))
            non-skipped-ids (remove util/uuid-nil? ids)
            await     (partial wait-for-workflow-complete env)
            results   (zipmap ids (map await non-skipped-ids))]
        (is (every? #{"Succeeded"} (vals results)) "One or more workflows failed")))))

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

(deftest test-copyfile-workload
  (with-temporary-gcs-folder uri
    (let [src (str/join [uri "input.txt"])
          dst (str/join [uri "output.txt"])]
      (->
       (str/join "/" ["test" "wfl" "resources" "copy-me.txt"])
       (gcs/upload-file src))
      (let [workload  (workloads/make-copyfile-workload src dst)
            await     (comp (partial wait-for-workflow-complete :gotc-dev) :uuid)
            submitted (endpoints/exec-workload workload)]
        (is (= (:pipeline submitted) cp/pipeline))
        (is (:started submitted))
        (let [result (-> submitted :workflows first await)]
          (is (= "Succeeded" result))
          (is (gcs/object-meta dst) "The file was not copied!"))))))

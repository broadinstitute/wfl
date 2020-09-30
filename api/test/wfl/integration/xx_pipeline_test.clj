(ns wfl.integration.xx-pipeline-test
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest testing is] :as clj-test]
            [wfl.module.copyfile :as cp]
            [wfl.service.cromwell :refer [wait-for-workflow-complete]]
            [wfl.service.gcs :as gcs]
            [wfl.tools.endpoints :as endpoints]
            [wfl.tools.fixtures :as fixtures]
            [wfl.tools.workloads :as workloads]
            [wfl.util :as util]
            [wfl.module.xx :as xx]
            [clojure.java.jdbc :as jdbc]
            [wfl.service.postgres :as postgres])
  (:import (java.util UUID)))

(clj-test/use-fixtures :once fixtures/clean-db-fixture)

(deftest test-populating-input-items
  (testing "create workload with google cloud storage url as `items`"
    (let [items (xx/input-items "gs://broad-gotc-test-storage/single_sample/load_50/truth/master/NWD101908.cram")]
      (is (= 1 (count items)))
      (let [input (first items)]
        (is :input_cram input)
        (is (not (:input_bam input)))))
    (let [items (xx/input-items "gs://broad-gotc-test-storage/single_sample/full/bams/HJYFJCCXX.4.Pond-492100.unmapped.bam")]
      (is (= 1 (count items)))
      (let [input (first items)]
        (is :input_bam input)
        (is (not (:input_cram input))))))
  (testing "supplying `items` maps is identity"
    (let [items {:fake (UUID/randomUUID)}]
      (is (= items (xx/input-items items))))))

(defn- make-xx-workload-request [id]
  (->
    (workloads/xx-workload id)
    (assoc :creator (:email @endpoints/userinfo))))

(defn add-workload! [workload-request]
  (jdbc/with-db-transaction [tx (postgres/wfl-db-config)]
    (->> workload-request
      (xx/add-workload! tx)
      :uuid
      (conj ["SELECT * FROM workload WHERE uuid = ?"])
      (jdbc/query tx))))

(defn start-workload!
  [workload-uuid]
  (letfn [(get-workload [tx uuid]
            (->>
              uuid
              (conj ["SELECT * FROM workload WHERE uuid = ?"])
              (jdbc/query tx)))]
    (jdbc/with-db-transaction [tx (postgres/wfl-db-config)]
      (->>
        workload-uuid
        (get-workload tx)
        (xx/start-workload! tx)
        (get-workload tx)))))

(deftest test-add-workload!
  (let [workload
        (->
          (UUID/randomUUID)
          (make-xx-workload-request)
          (add-workload!))]
    (is (not (:started workload)))))

(deftest test-start-workload!  )


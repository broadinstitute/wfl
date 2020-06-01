(ns zero.test.tools.stub-module
  "A dummy module for mocking a cromwell instance."
  (:require [clojure.data.json :as json]
            [clojure.java.jdbc :as jdbc]
            [clojure.string :as str]
            [zero.environments :as env]
            [zero.module.all :as all]
            [zero.module.wgs :as wgs]
            [zero.service.cromwell :as cromwell]
            [zero.service.gcs :as gcs]
            [zero.service.postgres :as postgres]
            [zero.service.gcs :as gcs]
            [zero.util :as util])
  (:import [java.time OffsetDateTime]))

(def pipeline "StubWorkload")
(def workflow-wdl
  {:release "stub-v1.0"
   :top     "test/tools/resources/stub.wdl"})

(defn update-workload!
  "Use transaction TX to update WORKLOAD statuses."
  [tx {:keys [cromwell items] :as workload}]
  ())

(defn add-workload!
  "Use transaction TX to add the workload described by BODY."
  [tx {:keys [items] :as body}]
  (let [now   (OffsetDateTime/now)
        [uuid table] (all/add-workload-table! tx workflow-wdl body)
        idnow (fn [item id] (-> item (assoc :id id :updated now)))]
    (jdbc/insert-multi! tx table (map idnow items (rest (range))))
    {:uuid uuid}))

(defn start-workload!
  "Use transaction TX to add the workload described by BODY."
  [tx {:keys [items] :as body}]
  ())

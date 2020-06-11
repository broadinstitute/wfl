(ns zero.module.copyfile
  "A dummy module for smoke testing wfl/cromwell auth."
  (:require [clojure.java.io :as io]
            [clojure.java.jdbc :as jdbc]
            [zero.module.all :as all]
            [zero.service.cromwell :as cromwell]
            [zero.service.postgres :as postgres]
            [zero.util :refer [prefix-keys make-options]])
  (:import [java.time OffsetDateTime]))

(def pipeline "copyfile")

(def workflow-wdl
  {:release "copyfile-v1.0"
   :top     "wdl/copyfile.wdl"})

(defn- submit-workflow
  "Submit INPUTS to be processed in ENVIRONMENT."
  [environment workflow]
  (let [make-inputs #(-> % (select-keys [:src :dst]) (prefix-keys pipeline))]
    (cromwell/submit-workflow
      environment
      (io/file (:top workflow-wdl))
      nil
      (make-inputs workflow)
      (make-options environment)
      {})))

(defn add-workload!
  "Use transaction TX to add the workload described by BODY."
  [tx {:keys [items] :as body}]
  (let [now   (OffsetDateTime/now)
        [uuid table] (all/add-workload-table! tx workflow-wdl body)
        idnow (fn [item id] (-> item (assoc :id id :updated now)))]
    (jdbc/insert-multi! tx table (map idnow items (rest (range))))
    {:uuid uuid}))

(defn start-workload!
  "Use transaction TX to start the WORKLOAD."
  [tx {:keys [cromwell items uuid]}]
  (let [env (first (all/cromwell-environments cromwell))
        now (OffsetDateTime/now)]
    (jdbc/update! tx :workload {:started now} ["uuid = ?" uuid])
    (letfn [(submit! [{:keys [id uuid] :as workflow}]
              [id (or uuid (submit-workflow env workflow))])
            (update! [tx [id uuid]]
              (when uuid
                (jdbc/update! tx items
                  {:updated now :uuid uuid}
                  ["id = ?" id])))]
      (let [workflow (postgres/get-table tx items)]
        (run! (comp (partial update! tx) submit!) workflow)))))

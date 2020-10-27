(ns wfl.module.copyfile
  "A dummy module for smoke testing wfl/cromwell auth."
  (:require [wfl.api.workloads :as workloads]
            [wfl.jdbc :as jdbc]
            [wfl.module.all :as all]
            [wfl.service.cromwell :as cromwell]
            [wfl.service.postgres :as postgres]
            [wfl.util :as util])
  (:import [java.time OffsetDateTime]))

(def pipeline "copyfile")

(def workflow-wdl
  {:release "copyfile-v1.0"
   :top     "wdl/copyfile.wdl"})

(defn- submit-workflow
  "Submit WORKFLOW to Cromwell in ENVIRONMENT."
  [environment workflow]
  (cromwell/submit-workflow
    environment
    (util/extract-resource (:top workflow-wdl))
    nil
    (-> workflow (select-keys [:src :dst]) (util/prefix-keys pipeline))
    (util/make-options environment)
    {}))

(defn add-copyfile-workload!
  "Use transaction TX to add the workload described by WORKLOAD-REQUEST."
  [tx {:keys [items] :as _workload-request}]
  (let [now          (OffsetDateTime/now)
        [uuid table] (all/add-workload-table! tx workflow-wdl _workload-request)
        item-inputs  (map :inputs items)]
    (letfn [(add-id-and-now [m id] (-> m (assoc :id id) (assoc :updated now)))]
      (jdbc/insert-multi! tx table (map add-id-and-now item-inputs (rest (range)))))
    uuid))

(defn start-copyfile-workload!
  "Use transaction TX to start _WORKLOAD."
  [tx {:keys [cromwell items uuid] :as _workload}]
  (let [env (first (all/cromwell-environments cromwell))
        now (OffsetDateTime/now)]
    (letfn [(submit! [{:keys [id uuid] :as workflow}]
              [id (or uuid (submit-workflow env workflow))])
            (update! [tx [id uuid]]
              (when uuid
                (jdbc/update! tx items
                  {:updated now :uuid uuid}
                  ["id = ?" id])))]
      (let [workflow (postgres/get-table tx items)]
        (jdbc/update! tx :workload {:started now} ["uuid = ?" uuid])
        (run! (comp (partial update! tx) submit!) workflow)))))

(defmethod workloads/load-workload-impl
  pipeline
  [tx workload]
  (workloads/load-workflow-with-structure
    tx workload {:inputs [:dst :src]}))

(defmethod workloads/create-workload!
  pipeline
  [tx request]
  (->>
    (add-copyfile-workload! tx request)
    (workloads/load-workload-for-uuid tx)))

(defmethod workloads/start-workload!
  pipeline
  [tx {:keys [id] :as workload}]
  (do
    (start-copyfile-workload! tx workload)
    (workloads/load-workload-for-id tx id)))

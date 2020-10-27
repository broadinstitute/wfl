(ns wfl.module.copyfile
  "A dummy module for smoke testing wfl/cromwell auth."
  (:require [wfl.api.workloads :as workloads]
            [wfl.jdbc :as jdbc]
            [wfl.module.all :as all]
            [wfl.service.cromwell :as cromwell]
            [wfl.util :as util])
  (:import [java.time OffsetDateTime]))

(def pipeline "copyfile")

(def workflow-wdl
  {:release "copyfile-v1.0"
   :top     "wdl/copyfile.wdl"})

(defn- submit-workflow
  "Submit WORKFLOW to Cromwell in ENVIRONMENT."
  [environment workflow labels]
  (cromwell/submit-workflow
    environment
    (util/extract-resource (:top workflow-wdl))
    nil
    (-> workflow (select-keys [:src :dst]) (util/prefix-keys pipeline))
    (util/make-options environment)
    labels))

(defn add-copyfile-workload!
  "Use transaction TX to add the workload described by WORKLOAD-REQUEST."
  [tx {:keys [items] :as _workload-request}]
  (let [default-options (-> (:cromwell _workload-request)
                            all/cromwell-environments
                            first
                            util/make-options)
        [uuid table]    (all/add-workload-table! tx workflow-wdl _workload-request default-options)
        inputs (map :inputs items)]
    (letfn [(add-id [m id] (assoc m :id id))]
      (jdbc/insert-multi! tx table (map add-id inputs (range))))
    uuid))

(defn start-copyfile-workload!
  "Use transaction TX to start _WORKLOAD."
  [tx {:keys [cromwell items uuid] :as workload}]
  (let [env (first (all/cromwell-environments cromwell))]
    (letfn [(submit! [{:keys [id inputs]}]
              [id (submit-workflow env inputs {:workload uuid}) "Submitted"])
            (update! [tx [id uuid status]]
              (jdbc/update! tx items
                {:updated (OffsetDateTime/now) :uuid uuid :status status}
                ["id = ?" id]))]
      (run! (comp (partial update! tx) submit!) (:workflows workload))
      (jdbc/update! tx :workload
        {:started (OffsetDateTime/now)} ["uuid = ?" uuid]))))

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

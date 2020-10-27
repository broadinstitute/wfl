(ns wfl.module.copyfile
  "A dummy module for smoke testing wfl/cromwell auth."
  (:require [clojure.data.json :as json]
            [wfl.api.workloads :as workloads]
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
  "Submit WORKFLOW to Cromwell in ENVIRONMENT with OPTIONS and LABELS."
  [environment workflow options labels]
  (cromwell/submit-workflow
    environment
    (util/extract-resource (:top workflow-wdl))
    nil
    (-> workflow (select-keys [:src :dst]) (util/prefix-keys pipeline))
    options
    labels))

(defn add-copyfile-workload!
  "Use transaction TX to add the workload described by WORKLOAD-REQUEST."
  [tx {:keys [items] :as _workload-request}]
  (let [default-options   (-> (:cromwell _workload-request)
                              all/cromwell-environments
                              first
                              util/make-options)
        [uuid table opts] (all/add-workload-table! tx workflow-wdl _workload-request default-options)
        to-row (fn [item] (assoc (:inputs item)
                            :workflow_options
                            (json/write-str (util/deep-merge opts (:workflow_options item)))))]
    (letfn [(add-id [m id] (assoc m :id id))]
      (jdbc/insert-multi! tx table (map add-id (map to-row items) (range))))
    uuid))

(defn start-copyfile-workload!
  "Use transaction TX to start _WORKLOAD."
  [tx {:keys [cromwell items uuid] :as workload}]
  (let [env (first (all/cromwell-environments cromwell))]
    (letfn [(submit! [{:keys [id inputs workflow_options]}]
              [id (submit-workflow env inputs workflow_options {:workload uuid}) "Submitted"])
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

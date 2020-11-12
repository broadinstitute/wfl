(ns wfl.module.copyfile
  "A dummy module for smoke testing wfl/cromwell auth."
  (:require [clojure.data.json :as json]
            [wfl.api.workloads :as workloads :refer [defoverload]]
            [wfl.jdbc :as jdbc]
            [wfl.module.all :as all]
            [wfl.module.batch :as batch]
            [wfl.service.cromwell :as cromwell]
            [wfl.util :as util])
  (:import [java.time OffsetDateTime]))

(def pipeline "copyfile")

(def workflow-wdl
  {:release "copyfile-v1.0"
   :top     "wdl/copyfile.wdl"})

(defn ^:private get-cromwell-environment [{:keys [cromwell]}]
  (let [envs (all/cromwell-environments #{:gotc-dev :gotc-prod} cromwell)]
    (when (not= 1 (count envs))
      (throw (ex-info "no unique environment matching Cromwell URL."
               {:cromwell     cromwell
                :environments envs})))
    (first envs)))

(defn ^:private submit-workflow
  "Submit WORKFLOW to Cromwell in ENVIRONMENT with OPTIONS and LABELS."
  [environment inputs options labels]
  (cromwell/submit-workflow
    environment
    (util/extract-resource (:top workflow-wdl))
    nil
    (-> inputs (util/prefix-keys pipeline))
    options
    labels))

(defn create-copyfile-workload!
  "Use transaction TX to add the workload described by REQUEST."
  [tx {:keys [items common] :as request}]
  (letfn [(merge-to-json [shared specific]
            (json/write-str (util/deep-merge shared specific)))
          (serialize [workflow id]
            (-> workflow
              (assoc :id id)
              (update :inputs #(merge-to-json (:inputs common) %))
              (update :options #(merge-to-json (:options common) %))))]
    (let [[id table] (batch/add-workload-table! tx workflow-wdl request)]
      (jdbc/insert-multi! tx table (map serialize items (range)))
      (workloads/load-workload-for-id tx id))))

(defn start-copyfile-workload!
  "Use transaction TX to start _WORKLOAD."
  [tx {:keys [items uuid] :as workload}]
  (let [env             (get-cromwell-environment workload)
        default-options (util/make-options env)]
    (letfn [(submit! [{:keys [id inputs options]}]
              [id (submit-workflow env inputs
                    (util/deep-merge default-options options)
                    {:workload uuid})])
            (update! [tx [id uuid]]
              (jdbc/update! tx items
                {:updated (OffsetDateTime/now) :uuid uuid :status "Submitted"}
                ["id = ?" id]))]
      (run! (comp (partial update! tx) submit!) (:workflows workload))
      (jdbc/update! tx :workload
        {:started (OffsetDateTime/now)} ["uuid = ?" uuid]))))

(defoverload workloads/create-workload! pipeline create-copyfile-workload!)

(defmethod workloads/start-workload!
  pipeline
  [tx {:keys [id] :as workload}]
  (do
    (start-copyfile-workload! tx workload)
    (workloads/load-workload-for-id tx id)))

(defmethod workloads/load-workload-impl
  pipeline
  [tx workload]
  (if (workloads/saved-before? "0.4.0" workload)
    (workloads/default-load-workload-impl tx workload)
    (batch/load-batch-workload-impl tx workload)))

(ns wfl.module.batch
  "Some utilities shared between batch workloads in cromwell."
  (:require [wfl.api.workloads :as workloads]
            [wfl.jdbc :as jdbc]
            [wfl.service.cromwell :as cromwell]
            [wfl.service.postgres :as postgres]
            [wfl.util :as util]
            [wfl.wfl :as wfl])
  (:import [java.time OffsetDateTime]
           [java.util UUID]))

(defn add-workload-table!
  "Use transaction `tx` to add a `CromwellWorkflow` table
  for a `workload-request` running `workflow-wdl`."
  [tx workflow-wdl workload-request]
  (let [{:keys [path release]} workflow-wdl
        {:keys [pipeline]} workload-request
        create "CREATE TABLE %s OF CromwellWorkflow (PRIMARY KEY (id))"
        setter "UPDATE workload SET pipeline = ?::pipeline WHERE id = ?"
        [{:keys [id]}]
        (-> workload-request
            (select-keys [:creator :executor :input :output :project])
            (update :executor util/de-slashify)
            (merge (select-keys (wfl/get-the-version) [:commit :version]))
            (assoc :release release :wdl path :uuid (UUID/randomUUID))
            (->> (jdbc/insert! tx :workload)))
        table (format "%s_%09d" pipeline id)]
    (jdbc/execute!       tx [setter pipeline id])
    (jdbc/db-do-commands tx [(format create table)])
    (jdbc/update!        tx :workload {:items table} ["id = ?" id])
    [id table]))

(defn load-batch-workload-impl
  "Use transaction `tx` to load and associate the workflows in the `workload`
  stored in a CromwellWorkflow table."
  [tx {:keys [items] :as workload}]
  (letfn [(unnilify [m] (into {} (filter second m)))
          (load-inputs [m] (update m :inputs (fnil util/parse-json "null")))
          (load-options [m] (update m :options (fnil util/parse-json "null")))]
    (->> (postgres/get-table tx items)
         (mapv (comp unnilify load-options load-inputs))
         (assoc workload :workflows)
         load-options
         unnilify)))

(defn submit-workload!
  "Use transaction TX to start the WORKLOAD."
  ([{:keys [uuid workflows]} url workflow-wdl make-cromwell-inputs! cromwell-label default-options]
   (letfn [(update-workflow [workflow cromwell-uuid]
             (assoc workflow :uuid cromwell-uuid
                    :status "Submitted"
                    :updated (OffsetDateTime/now)))
           (submit-batch! [[options workflows]]
             (map update-workflow
                  workflows
                  (cromwell/submit-workflows
                   url
                   workflow-wdl
                   (map (partial make-cromwell-inputs! url) workflows)
                   (util/deep-merge default-options options)
                   (merge cromwell-label {:workload uuid}))))]
     (mapcat submit-batch! (group-by :options workflows)))))

(defn update-workload!
  "Use transaction TX to batch-update WORKLOAD statuses."
  [tx workload]
  (letfn [(update! [{:keys [id]}]
            (postgres/batch-update-workflow-statuses! tx workload)
            (postgres/update-workload-status! tx workload)
            (workloads/load-workload-for-id tx id))]
    (util/unless-> workload #(or (:finished %) (not (:started %))) update!)))

(defn stop-workload!
  "Use transaction TX to stop the WORKLOAD."
  [tx {:keys [id] :as workload}]
  (letfn [(patch! [colls {:keys [items]}]
            (jdbc/update! tx items colls ["id = ?" id]))
          (stop! [_]
            (let [now (OffsetDateTime/now)]
              (util/unless-> workload :started #(patch! {:started now
                                                         :finished now} %))
              (patch! {:stopped now} workload)
              (workloads/load-workload-for-id tx id)))]
    (util/unless-> workload #(or (:stopped %) (:finished %)) stop!)))

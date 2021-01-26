(ns wfl.module.batch
  "Some utilities shared between batch workloads in cromwell."
  (:require [clojure.string :as str]
            [wfl.api.workloads :as workloads]
            [wfl.jdbc :as jdbc]
            [wfl.module.all :as all]
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
        kindit "UPDATE workload SET pipeline = ?::pipeline WHERE id = ?"
        [{:keys [id]}]
        (-> workload-request
            (select-keys [:creator :executor :input :output :project])
            (update :executor all/de-slashify)
            (merge (select-keys (wfl/get-the-version) [:commit :version]))
            (assoc :release release :wdl path :uuid (UUID/randomUUID))
            (->> (jdbc/insert! tx :workload)))
        table (format "%s_%09d" pipeline id)]
    (jdbc/execute!       tx [kindit pipeline id])
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
  ([workload env workflow-wdl make-cromwell-inputs! cromwell-label]
   (submit-workload! workload env workflow-wdl
                     make-cromwell-inputs!
                     cromwell-label
                     (util/make-options env)))
  ([workload env workflow-wdl make-cromwell-inputs! cromwell-label
    default-options]
   (let [{:keys [uuid workflows]} workload]
     (letfn [(update-workflow [workflow cromwell-uuid]
               (assoc workflow
                      ;; :status "Submitted" ; Manage in update. =tbl
                      :updated (OffsetDateTime/now)
                      :uuid   cromwell-uuid))
             (submit-batch! [[options workflows]]
               (map update-workflow
                    workflows
                    (cromwell/submit-workflows
                     env workflow-wdl
                     (map (partial make-cromwell-inputs! env) workflows)
                     (util/deep-merge default-options options)
                     (merge cromwell-label {:workload uuid}))))]
       (mapcat submit-batch! (group-by :options workflows))))))

(defn update-workload!
  "Use transaction TX to batch-update WORKLOAD statuses."
  [tx {:keys [id] :as workload}]
  (if (or (:finished workload) (not (:started workload)))
    workload
    (do
      (postgres/batch-update-workflow-statuses! tx workload)
      (postgres/update-workload-status! tx workload)
      (workloads/load-workload-for-id tx id))))

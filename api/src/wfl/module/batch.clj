(ns wfl.module.batch
  "Some utilities shared between batch workloads in cromwell."
  (:require [clojure.java.io :as io]
            [clojure.pprint :refer [pprint]]
            [wfl.jdbc :as jdbc]
            [wfl.module.all :as all]
            [wfl.service.cromwell :as cromwell]
            [wfl.service.postgres :as postgres]
            [wfl.util :as util]
            [wfl.wdl :as wdl]
            [wfl.wfl :as wfl])
  (:import [java.util UUID]
           [java.time OffsetDateTime]))

(defn add-workload-table!
  "Create row in workload table for `workload-request` using transaction `tx`.
  Instantiate a CromwellWorkflow table for the workload.
  Returns: [id table-name]"
  [tx {:keys [release top] :as _workflow-wdl} {:keys [pipeline] :as workload-request}]
  (let [[{:keys [id]}]
        (-> workload-request
            (select-keys [:creator :cromwell :input :output :project])
            (update :cromwell all/de-slashify)
            (merge (select-keys (wfl/get-the-version) [:commit :version]))
            (assoc :release release :wdl top :uuid (UUID/randomUUID))
            (->> (jdbc/insert! tx :workload)))
        table (format "%s_%09d" pipeline id)]
    (jdbc/execute! tx
                   ["UPDATE workload SET pipeline = ?::pipeline WHERE id = ?" pipeline id])
    (jdbc/db-do-commands tx
                         (map #(format "CREATE TABLE %s OF CromwellWorkflow (PRIMARY KEY (id))" %)
                              [table]))
    (jdbc/update! tx :workload {:items table} ["id = ?" id])
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
  ([workflow env workflow-wdl make-cromwell-inputs! cromwell-label]
   (submit-workload! workflow env workflow-wdl make-cromwell-inputs! cromwell-label (util/make-options env)))
  ([{:keys [uuid workflows]} env workflow-wdl make-cromwell-inputs! cromwell-label default-options]
   (let [path            (wdl/hack-unpack-resources-hack (:top workflow-wdl))]
     (letfn [(update-workflow [workflow cromwell-uuid]
               (assoc workflow :uuid cromwell-uuid
                      :status "Submitted"
                      :updated (OffsetDateTime/now)))
             (submit-batch! [[options workflows]]
               (map update-workflow
                    workflows
                    (cromwell/submit-workflows
                     env
                     (io/file (:dir path) (path ".wdl"))
                     (let [dependencies (io/file (:dir path) (path ".zip"))]
                       (if (.exists dependencies) dependencies nil))
                     (map (partial make-cromwell-inputs! env) workflows)
                     (util/deep-merge default-options options)
                     (merge cromwell-label {:workload uuid}))))]
       (mapcat submit-batch! (group-by :options workflows))))))

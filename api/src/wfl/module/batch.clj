(ns wfl.module.batch
  "Some utilities shared between batch workloads in cromwell."
  (:require [clojure.data.json :as json]
            [clojure.pprint :refer [pprint]]
            [wfl.jdbc :as jdbc]
            [wfl.wfl :as wfl]
            [wfl.util :as util])
  (:import [java.util UUID]))

(defn add-workload-table!
  "Create row in workload table for `workload-request` using transaction `tx`.
  Instantiate a CromwellWorkflow table for the workload.
  Returns: [id table-name]"
  ([tx workflow-wdl workload-request]
   (add-workload-table! tx workflow-wdl workload-request {}))
  ([tx
   {:keys [release top] :as _workflow-wdl}
   {:keys [pipeline] :as workload-request}
   default-workflow-options]
  (let [workflow-options (util/deep-merge default-workflow-options (:workflow_options workload-request))
        [{:keys [id]}]
        (-> workload-request
          (select-keys [:creator :cromwell :input :output :project])
          (merge (-> (wfl/get-the-version) (select-keys [:commit :version])))
          (assoc :release release
                 :wdl top
                 :uuid (UUID/randomUUID)
                 :workflow_options (json/write-str workflow-options))
          (->> (jdbc/insert! tx :workload)))
        table (format "%s_%09d" pipeline id)]
    (jdbc/execute! tx
      ["UPDATE workload SET pipeline = ?::pipeline WHERE id = ?" pipeline id])
    (jdbc/db-do-commands tx
      (map #(format "CREATE TABLE %s OF CromwellWorkflow (PRIMARY KEY (id))" %)
        [table]))
    (jdbc/update! tx :workload {:items table} ["id = ?" id])
    [id table workflow-options])))

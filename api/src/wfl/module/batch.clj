(ns wfl.module.batch
  "Some utilities shared between batch workloads in cromwell."
  (:require [clj-http.client      :as http]
            [clojure.string       :as str]
            [wfl.api.workloads    :as workloads]
            [wfl.auth             :as auth]
            [wfl.jdbc             :as jdbc]
            [wfl.service.cromwell :as cromwell]
            [wfl.service.postgres :as postgres]
            [wfl.util             :as util]
            [wfl.wfl              :as wfl])
  (:import [java.time OffsetDateTime]
           [java.util UUID]))

(defn ^:private cromwell-status
  "`status` of the workflow with `uuid` in `cromwell`."
  [cromwell uuid]
  (-> (str/join "/" [cromwell "api" "workflows" "v1" uuid "status"])
      (http/get {:headers (auth/get-auth-header)})
      :body
      util/parse-json
      :status))

(def ^:private finished?
  "Test if a workflow `:status` is in a terminal state."
  (set (conj cromwell/final-statuses "skipped")))

(defn make-update-workflows [get-status!]
  (fn [tx {:keys [items] :as workload}]
    (letfn [(update! [{:keys [id uuid]} status]
              (jdbc/update! tx items
                            {:status status
                             :updated (OffsetDateTime/now)
                             :uuid uuid}
                            ["id = ?" id]))]
      (->> (workloads/workflows workload)
           (remove (comp nil? :uuid))
           (remove (comp finished? :status))
           (run! #(update! % (get-status! workload %)))))))

(def update-workflow-statuses!
  "Use `tx` to update `status` of Cromwell `workflows` in a `workload`."
  (letfn [(get-cromwell-status [{:keys [executor]} {:keys [uuid]}]
            (if (util/uuid-nil? uuid)
              "skipped"
              (cromwell-status executor uuid)))]
    (make-update-workflows get-cromwell-status)))

(defn batch-update-workflow-statuses!
  "Use `tx` to update the `status` of the workflows in `_workload`."
  [tx {:keys [executor uuid items] :as _workoad}]
  (let [uuid->status (->> {:label (str "workload:" uuid) :includeSubworkflows "false"}
                          (cromwell/query executor)
                          (map (juxt :id :status)))]
    (letfn [(update! [[uuid status]]
              (jdbc/update! tx items
                            {:status status :updated (OffsetDateTime/now)}
                            ["uuid = ?" uuid]))]
      (run! update! uuid->status))))

(defn active-workflows
  "Use `tx` to query all the workflows in `_workload` whose :status is not in
  `finished?`"
  [tx {:keys [items] :as _workload}]
  (let [query "SELECT id FROM %s WHERE status IS NULL OR status NOT IN %s"]
    (->> (util/to-quoted-comma-separated-list finished?)
         (format query items)
         (jdbc/query tx))))

(defn update-workload-status!
  "Use `tx` to mark `workload` finished when all `workflows` are finished."
  [tx {:keys [id] :as workload}]
  (when (empty? (active-workflows tx workload))
    (jdbc/update! tx :workload {:finished (OffsetDateTime/now)} ["id = ?" id])))

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
            (select-keys [:creator :executor :input :output :project :watchers :labels])
            (update :executor util/de-slashify)
            (merge (select-keys (wfl/get-the-version) [:commit :version]))
            (assoc :release release :wdl path :uuid (UUID/randomUUID))
            (->> (jdbc/insert! tx :workload)))
        table (format "%s_%09d" pipeline id)]
    (jdbc/execute!       tx [setter pipeline id])
    (jdbc/db-do-commands tx [(format create table)])
    (jdbc/update!        tx :workload {:items table} ["id = ?" id])
    [id table]))

(defn pre-v0.4.0-load-workload-impl
  [tx workload]
  (letfn [(unnilify [m] (into {} (filter second m)))
          (split-inputs [m]
            (let [keep [:id :finished :status :updated :uuid :options]]
              (assoc (select-keys m keep) :inputs (apply dissoc m keep))))
          (load-options [m] (update m :options (fnil util/parse-json "null")))]
    (->> (postgres/get-table tx (:items workload))
         (mapv (comp unnilify split-inputs load-options))
         (assoc workload :workflows)
         unnilify)))

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
  [tx {:keys [started finished] :as workload}]
  (letfn [(update! [{:keys [id] :as workload}]
            (batch-update-workflow-statuses! tx workload)
            (update-workload-status! tx workload)
            (workloads/load-workload-for-id tx id))]
    (if (and started (not finished)) (update! workload) workload)))

(defn stop-workload!
  "Use transaction TX to stop the WORKLOAD."
  [tx {:keys [stopped finished id] :as workload}]
  (letfn [(patch! [cols] (jdbc/update! tx :workload cols ["id = ?" id]))
          (stop! [{:keys [id] :as workload}]
            (let [now (OffsetDateTime/now)]
              (patch! {:stopped now})
              (when-not (:started workload) (patch! {:finished now}))
              (workloads/load-workload-for-id tx id)))]
    (if-not (or stopped finished) (stop! workload) workload)))

(defn workflows
  "Return the workflows managed by the `workload`."
  [workload]
  (:workflows workload))

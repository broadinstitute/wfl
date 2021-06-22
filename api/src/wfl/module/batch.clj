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
           [java.util UUID]
           [wfl.util UserException]))

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
      (->> (workloads/workflows tx workload)
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
  (let [uuid->status (->> {:includeSubworkflows "false"
                           :label               (str "workload:" uuid)}
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

(defn load-batch-workload-impl
  "Load workload metadata + trim any unused vars."
  [_ workload]
  (into {:type :workload} (filter second workload)))

(defn submit-workload!
  "Submit the `workflows` to Cromwell with `url`."
  [{:keys [uuid labels] :as _workload}
   workflows
   url
   workflow-wdl
   make-cromwell-inputs!
   cromwell-label
   default-options]
  (letfn [(update-workflow [workflow cromwell-uuid]
            (merge workflow {:uuid cromwell-uuid
                             :status "Submitted"
                             :updated (OffsetDateTime/now)}))
          (submit-batch! [[options workflows]]
            (map update-workflow
                 workflows
                 (cromwell/submit-workflows
                  url
                  workflow-wdl
                  (map (partial make-cromwell-inputs! url) workflows)
                  (util/deep-merge default-options options)
                  (merge
                   cromwell-label
                   {:workload uuid}
                   (->> labels
                        (map #(-> % (str/split #":" 2) (update 0 keyword)))
                        (into {}))))))]
    (->> workflows
         (group-by :options)
         (mapcat submit-batch!))))

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
  [tx {:keys [started stopped finished id] :as workload}]
  (letfn [(patch! [cols] (jdbc/update! tx :workload cols ["id = ?" id]))
          (stop! [{:keys [id] :as workload}]
            (let [now (OffsetDateTime/now)]
              (patch! {:stopped now})
              (when-not (:started workload) (patch! {:finished now}))
              (workloads/load-workload-for-id tx id)))]
    (when-not started
      (throw (UserException. "Cannot stop workload before it's been started."
                             {:workload workload})))
    (if-not (or stopped finished) (stop! workload) workload)))

(defn pre-v0_4_0-deserialize-workflows
  [workflows]
  (letfn [(split-inputs [m]
            (let [keep [:id :finished :status :updated :uuid :options]]
              (assoc (select-keys m keep) :inputs (apply dissoc m keep))))
          (load-options [m] (update m :options (fnil util/parse-json "null")))]
    (mapv (comp util/unnilify split-inputs load-options) workflows)))

(defn ^:private post-v0_4_0-deserialize-workflows
  [workflows]
  (letfn [(load-inputs [m] (update m :inputs (fnil util/parse-json "null")))
          (load-options [m] (update m :options (fnil util/parse-json "null")))]
    (mapv (comp util/unnilify load-options load-inputs) workflows)))

(defn ^:private deserialize-workflows
  [workload records]
  (if (workloads/saved-before? "0.4.0" workload)
    (pre-v0_4_0-deserialize-workflows records)
    (post-v0_4_0-deserialize-workflows records)))

(defn tag-workflows
  "Associate the :batch-workflow :type in all `workflows`."
  [workflows]
  (map #(assoc % :type :batch-workflow) workflows))

(defn query-workflows-with-status
  "Return the workflows in the items `table` that match `status`."
  [tx table status]
  (when-not (and table (postgres/table-exists? tx table))
    (throw (ex-info "Table not found" {:table table})))
  (let [query-str "SELECT * FROM %s WHERE status = ? ORDER BY id ASC"]
    (jdbc/query tx [(format query-str table) status])))

(defn workflows
  "Return the workflows managed by the `workload`."
  ([tx {:keys [items] :as workload}]
   (tag-workflows
    (deserialize-workflows workload (postgres/get-table tx items))))
  ([tx {:keys [items] :as workload} status]
   (tag-workflows
    (deserialize-workflows
     workload
     (query-workflows-with-status tx items status)))))

(defn retry-unsupported
  [workload _workloads]
  (throw (UserException. "Cannot retry workflows - operation unsupported."
                         {:workload (util/to-edn workload)
                          :status   501})))

(defmethod util/to-edn :batch-workflow [workflow] (dissoc workflow :id :type))

(defn workload-to-edn
  "Return a user-friendly EDN representation of the batch `workload`"
  [workload]
  (util/select-non-nil-keys
   workload
   [:commit
    :created
    :creator
    :executor
    :finished
    :input
    :output
    :pipeline
    :project
    :release
    :started
    :stopped
    :uuid
    :version
    :wdl]))

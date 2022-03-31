(ns wfl.module.batch
  "Some utilities shared between batch workloads in cromwell."
  (:require [clj-http.client      :as http]
            [clojure.spec.alpha   :as s]
            [clojure.string       :as str]
            [clojure.edn          :as edn]
            [wfl.api.workloads    :as workloads]
            [wfl.auth             :as auth]
            [wfl.jdbc             :as jdbc]
            [wfl.module.all       :as all]
            [wfl.service.cromwell :as cromwell]
            [wfl.service.postgres :as postgres]
            [wfl.sink             :as sink]
            [wfl.source           :as source]
            [wfl.util             :as util]
            [wfl.wfl              :as wfl])
  (:import [java.time OffsetDateTime]
           [java.util UUID]
           [wfl.util UserException]))

;;specs
(s/def ::executor string?)
(s/def ::creator string?)

;; This is the wrong thing to do. See [1] for more information.
;; As a consequence, I've included the keys for a staged pipeline
;; as optional inputs for batch workloads
;; so that these keys are not removed during coercion.
;; [1]: https://github.com/metosin/reitit/issues/494
;;
(s/def ::workload-request
  (s/keys :opt-un [::all/common
                   ::all/input
                   :wfl.api.spec/items
                   ::all/labels
                   ::all/output
                   ::sink/sink
                   ::source/source
                   ::all/watchers]
          :req-un [(or ::all/cromwell ::executor)
                   ::all/pipeline
                   ::all/project]))

(s/def ::workload-response (s/keys :opt-un [::all/finished
                                            ::all/input
                                            ::all/started
                                            ::all/stopped
                                            ::all/wdl]
                                   :req-un [::all/commit
                                            ::all/created
                                            ::creator
                                            ::executor
                                            ::all/output
                                            ::all/pipeline
                                            ::all/project
                                            ::all/release
                                            ::all/uuid
                                            ::all/version]))

(s/def ::workflow  (s/keys :opt-un [::all/options
                                    ::all/status
                                    ::all/updated
                                    ::all/uuid]
                           :req-un [:wfl.api.spec/inputs]))

(defn ^:private cromwell-status
  "`status` of the workflow with `uuid` in `cromwell`."
  [cromwell uuid]
  (-> (str/join "/" [cromwell "api" "workflows" "v1" uuid "status"])
      (http/get {:headers (auth/get-auth-header)})
      :body
      util/parse-json
      :status))

(defn make-update-workflows
  "Call `get-status!` under `tx` to update workflow statuses in `workload`."
  [get-status!]
  (fn [tx {:keys [items] :as workload}]
    (letfn [(update! [{:keys [id uuid]} status]
              (jdbc/update! tx items
                            {:status status
                             :updated (OffsetDateTime/now)
                             :uuid uuid}
                            ["id = ?" id]))]
      (->> (workloads/workflows tx workload)
           (remove (comp nil? :uuid))
           (remove (comp cromwell/final? :status))
           (run! #(update! % (get-status! workload %)))))))

(def update-workflow-statuses!
  "Update the status of `_workflow` in `workload`."
  (letfn [(get-cromwell-status [{:keys [executor] :as _workload}
                                {:keys [uuid]     :as _workflow}]
            (cromwell-status executor uuid))]
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
  `final?`"
  [tx {:keys [items] :as _workload}]
  (let [query "SELECT id FROM %s WHERE status IS NULL OR status NOT IN %s"]
    (->> (util/to-quoted-comma-separated-list cromwell/final?)
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
            (update :watchers pr-str)
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
  (update
   (into {:type :workload} (filter second workload))
   :watchers
   edn/read-string))

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
  "Batch-update `workload-record` statuses."
  [{:keys [id started finished] :as _workload-record}]
  (jdbc/with-db-transaction [tx (postgres/wfl-db-config)]
    (letfn [(load-workload []
              (workloads/load-workload-for-id tx id))
            (update!       [workload]
              (batch-update-workflow-statuses! tx workload)
              (update-workload-status! tx workload)
              (load-workload))]
      (if (and started (not finished))
        (update! (load-workload))
        (load-workload)))))

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
  (postgres/throw-unless-table-exists tx table)
  (let [query-str "SELECT * FROM %s WHERE status = ? ORDER BY id ASC"]
    (jdbc/query tx [(format query-str table) status])))

(defn workflows
  "Return the workflows managed by the `workload`."
  [tx {:keys [items] :as workload}]
  (tag-workflows
   (deserialize-workflows workload (postgres/get-table tx items))))

(defn workflows-by-filters
  "Return the workflows managed by the `workload` matching `status`."
  [tx {:keys [items] :as workload} {:keys [status] :as _filters}]
  (tag-workflows
   (deserialize-workflows
    workload
    (query-workflows-with-status tx items status))))

(defn retry-unsupported
  [workload _]
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
    :watchers
    :wdl]))

(ns wfl.module.covid
  "Manage the Sarscov2IlluminaFull pipeline."
  (:require [clojure.edn :as edn]
            [clojure.data.json :as json]
            [clojure.set :as set]
            [clojure.string :as str]
            [clojure.tools.logging :as log]
            [wfl.api.workloads :as workloads :refer [defoverload]]
            [wfl.jdbc :as jdbc]
            [wfl.module.batch :as batch]
            [wfl.service.datarepo :as datarepo]
            [wfl.service.firecloud :as firecloud]
            [wfl.service.postgres :as postgres]
            [wfl.service.rawls :as rawls]
            [wfl.util :as util]
            [wfl.wfl :as wfl])
  (:import [java.time OffsetDateTime ZoneId]
           [java.time.format DateTimeFormatter]
           [java.util UUID]))

(def pipeline "Sarscov2IlluminaFull")

(defn ^:private get-snapshots-from-workspace
  [workspace])

;; Generic helpers
(defn ^:private load-record-by-id! [tx table id]
  (let [query        "SELECT * FROM %s WHERE id = ? LIMIT 1"
        [record & _] (jdbc/query tx [(format query table) id])]
    (when-not record
      (throw (ex-info (str "No such record") {:id id :table table})))
    record))

(defn ^:private utc-now
  "Return OffsetDateTime/now in UTC."
  []
  (OffsetDateTime/now (ZoneId/of "UTC")))

;; interfaces
;; queue operations
(defmulti peek-queue!
  "Peek the first object from the `queue`, if one exists."
  (fn [queue] (:type queue)))

(defmulti pop-queue!
  "Pop the first object from the `queue`. Throws if none exists."
  (fn [queue] (:type queue)))

;; source operations
(defmulti create-source!
  "Use `tx` and workload `id` to write the source to persisted storage and
   return a [type item] pair to be written into the parent table."
  (fn [tx id source-request] (:name source-request)))

(defmulti start-source!
  "Use `tx` to start accepting data from the `source`."
  (fn [tx source] (:type source)))

(defmulti update-source!
  "Update the source."
  (fn [source] (:type source)))

(defmulti load-source!
  "Use `tx` to load the workload source with `source_type`."
  (fn [tx workload] (:source_type workload)))

;; executor operations
(defmulti create-executor!
  "Use `tx` and workload `id` to write the executor to persisted storage and
   return a [type item] pair to be written into the parent table."
  (fn [tx id executor-request] (:name executor-request)))

(defmulti update-executor!
  "Update the executor with the `source`"
  (fn [source executor] (:type executor)))

(defmulti executor-workflows
  "Use `tx` to return the workflows created by the `executor"
  (fn [tx executor] (:type executor)))

(defmulti load-executor!
  "Use `tx` to load the workload executor with `executor_type`."
  (fn [tx workload] (:executor_type workload)))

;; sink operations
(defmulti create-sink!
  "Use `tx` and workload `id` to write the sink to persisted storage and
   return a [type item] pair to be written into the parent table."
  (fn [tx id sink-request] (:name sink-request)))

(defmulti update-sink!
  "Update the sink with the `executor`"
  (fn [executor sink] (:type sink)))

(defmulti load-sink!
  "Use `tx` to load the workload sink with `sink_type`."
  (fn [tx workload] (:sink_type workload)))

(defn ^:private add-continuous-workload-record
  "Use `tx` and workload `id` to create a \"ContinuousWorkload\" instance and
  return the ID of the ContinuousWorkload."
  [tx id {:keys [source sink executor] :as _request}]
  (let [set-details "UPDATE
                         ContinuousWorkload
                     SET
                         source_type   = ?::source,
                         executor_type = ?::executor,
                         sink_type     = ?::sink
                     WHERE
                         id = ? "
        src-exc-snk [(create-source! tx id source)
                     (create-executor! tx id executor)
                     (create-sink! tx id sink)]
        items       (->> (map second src-exc-snk)
                         (zipmap [:source_items :executor_items :sink_items])
                         (jdbc/insert! tx :ContinuousWorkload)
                         first
                         :id)]
    (jdbc/execute! tx (concat [set-details] (map first src-exc-snk) [items]))
    items))

(defn ^:private add-workload-record
  "Use `tx` to create a workload `record` for `request` and return the id of the
   new workload."
  [tx request]
  (letfn [(combine-labels [labels]
            (->> (mapv request [:pipeline :project])
                 (map str ["pipeline:" "project:"])
                 (concat labels)
                 set
                 sort
                 vec))]
    (-> (update request :labels combine-labels)
        (select-keys [:creator :watchers :labels :project])
        (merge (select-keys (wfl/get-the-version) [:commit :version]))
        (assoc :executor "" :output "" :release "" :wdl "" :uuid (UUID/randomUUID))
        (->> (jdbc/insert! tx :workload) first :id))))

;; TODO: validate the request before creating the workload
(defn create-covid-workload [tx request]
  (let [set-pipeline "UPDATE workload
                      SET pipeline = ?::pipeline
                      WHERE id = ?"
        id           (add-workload-record tx request)
        items        (add-continuous-workload-record tx id request)]
    (jdbc/execute! tx [set-pipeline pipeline id])
    (jdbc/update! tx :workload {:items items} ["id = ?" id])
    (workloads/load-workload-for-id tx id)))

(defn start-covid-workload
  "Start creating and managing workflows from the source."
  [tx {:keys [id source] :as _workload}]
  (start-source! tx source)
  (jdbc/update! tx :workload {:started (OffsetDateTime/now)} ["id = ?" id])
  (workloads/load-workload-for-id tx id))

(defn update-covid-workload
  "Use transaction TX to update WORKLOAD statuses."
  [tx {:keys [started finished] :as workload}]
  (letfn [(update-workload-status [])
          (update! [{:keys [id source executor sink] :as _workload}]
            (-> (update-source! source)
                (update-executor! executor)
                (update-sink! sink))
            (update-workload-status)
            (workloads/load-workload-for-id tx id))]
    (if (and started (not finished)) (update! workload) workload)))

(defn load-covid-workload-impl [tx {:keys [items] :as workload}]
  (if-let [id (util/parse-int items)]
    (let [details (load-record-by-id! tx "ContinuousWorkload" id)]
      (->> {:source   (load-source! tx details)
            :executor (load-executor! tx details)
            :sink     (load-sink! tx details)}
           (merge workload)
           (filter second)
           (into {})))
    (throw (ex-info "Invalid ContinuousWorkload identifier"
                    {:id       items
                     :workload workload}))))

;; Terra Data Repository Source
(def ^:private tdr-source-name  "Terra DataRepo")
(def ^:private tdr-source-type  "TerraDataRepoSource")
(def ^:private tdr-source-table "TerraDataRepoSource")
(def ^:private tdr-source-serialized-fields
  {:dataset         :dataset
   :table           :dataset_table
   :column          :table_column_name
   :snapshotReaders :snapshot_readers})

(defn ^:private create-tdr-source [tx id request]
  (let [create  "CREATE TABLE %s OF TerraDataRepoSourceDetails (PRIMARY KEY (id))"
        alter   "ALTER TABLE %s ALTER COLUMN id ADD GENERATED ALWAYS AS IDENTITY"
        details (format "TerraDataRepoSourceDetails_%09d" id)
        _       (jdbc/db-do-commands tx [(format create details)
                                         (format alter details)])
        items   (-> (select-keys request (keys tdr-source-serialized-fields))
                    (update :dataset pr-str)
                    (set/rename-keys tdr-source-serialized-fields)
                    (assoc :details details)
                    (->> (jdbc/insert! tx tdr-source-table)))]
    [tdr-source-type (-> items first :id str)]))

(defn ^:private load-tdr-source [tx {:keys [source_items] :as workload}]
  (if-let [id (util/parse-int source_items)]
    (-> (load-record-by-id! tx tdr-source-table id)
        (set/rename-keys (set/map-invert tdr-source-serialized-fields))
        (assoc :type tdr-source-type)
        (update :dataset edn/read-string))
    (throw (ex-info "source_items is not an integer" {:workload workload}))))

(defn ^:private find-new-rows
  "Find new rows in TDR by querying between `last_checked` and the
   frozen `now`."
  [{:keys [dataset
           dataset_table
           table_column_name
           last_checked] :as _source}
   now]
  (-> (datarepo/query-table-between
       dataset
       dataset_table
       table_column_name
       [last_checked now]
       [:datarepo_row_id])
      :rows
      flatten))

(defn ^:private go-create-snapshot!
  "Create snapshot in TDR from `dataset` body, `table` and `row-ids` then
   write job info as well as rows into `source-details-name` table.
   Snapshots will be readable by members of the `snapshotReaders` list.
   `suffix` will be appended to the snapshot names."
  [suffix {:keys [dataset table snapshotReaders] :as _source} row-ids]
  (let [columns (->> (datarepo/all-columns dataset table)
                     (mapv :name)
                     (cons "datarepo_row_id"))]
    (-> (datarepo/make-snapshot-request dataset columns table row-ids)
        (update :name #(str % suffix))
        (assoc :readers snapshotReaders)
        datarepo/create-snapshot-job)))

(defn ^:private create-snapshots
  "Create uniquely named snapshots in TDR with max partition size of 500,
   using the frozen `now-obj`, from `row-ids`, return shards and TDR job-ids."
  [{:keys [dataset table snapshotReaders] :as source} now-obj row-ids]
  (let [dt-format   (DateTimeFormatter/ofPattern "YYYYMMdd'T'HHmmss")
        compact-now (.format now-obj dt-format)]
    (letfn [(create-snapshot [idx shard]
              [shard (-> (format "_%s_%s" compact-now idx)
                         (go-create-snapshot! source shard))])]
      (->> row-ids
           (partition-all 500)
           (map vec)
           (map-indexed create-snapshot)))))

(defn ^:private get-pending-tdr-jobs [{:keys [details] :as _source}]
  (let [query "SELECT id, snapshot_creation_job_id
               FROM %s
               WHERE snapshot_creation_job_status = 'running'"]
    (jdbc/with-db-transaction [tx (postgres/wfl-db-config)]
      (->> (format query details)
           (jdbc/query tx)
           (map (juxt :id :snapshot_creation_job_id))))))

(defn ^:private check-tdr-job
  "Check TDR job status for `job-id`, return a map with job-id,
   snapshot_id and job_status if job has failed or succeeded, otherwise nil."
  [job-id]
  (when-let [job-metadata (datarepo/get-job-metadata-when-done job-id)]
    (let [{:keys [id job-status] :as result} job-metadata]
      (if (= job-status "succeeded")
        (assoc result :snapshot_id (:id (datarepo/get-job-result id)))
        (do
          (log/error "TDR Snapshot creation job %s failed!" id)
          (assoc result :snapshot_id nil))))))

(defn ^:private write-snapshot-id
  "Write `snapshot_id` and `job_status` into source `details` table
   from the `_tdr-job-metadata` map, update timestamp with real now."
  [{:keys [details] :as _source}
   [id {:keys [job_status snapshot_id] :as _tdr-job-metadata}]]
  (jdbc/with-db-transaction [tx (postgres/wfl-db-config)]
    (jdbc/update! tx details {:snapshot_creation_job_status job_status
                              :snapshot_id snapshot_id
                              :updated  (OffsetDateTime/now)}
                  ["id = ?" id])))

(defn ^:private write-snapshots-creation-jobs
  "Write the shards and corresponding snapshot creation jobs from
   `shards->snapshot-jobs` into source `details` table, with the frozen `now`.
   Also initialize all jobs statuses to running."
  [{:keys [last_checked details] :as _source} now shards->snapshot-jobs]
  (letfn [(make-record [[shard id]]
            {:snapshot_creation_job_id     id
             :snapshot_creation_job_status "running"
             :datarepo_row_ids             shard
             :start_time                   last_checked
             :end_time                     now})]
    (jdbc/with-db-transaction [tx (postgres/wfl-db-config)]
      (->> (map make-record shards->snapshot-jobs)
           (jdbc/insert-multi! tx details)))))

(defn ^:private update-last-checked
  "Update the `last_checked` field in source table with
   the frozen `now`."
  ([tx {:keys [id] :as _source} now]
   (jdbc/update! tx tdr-source-table {:last_checked now} ["id = ?" id]))
  ([source now]
   (jdbc/with-db-transaction [tx (postgres/wfl-db-config)]
     (update-last-checked tx source now))))

(defn ^:private find-and-snapshot-new-rows
  "Create and enqueue snapshots from new rows in the `source` dataset."
  [source utc-now]
  (let [date-format (DateTimeFormatter/ofPattern "yyyy-MM-dd'T'HH:mm:ss")]
    (->> (.format utc-now date-format)
         (find-new-rows source)
         (create-snapshots source utc-now)
         (write-snapshots-creation-jobs source utc-now))
    (update-last-checked source utc-now)))

(defn ^:private update-pending-snapshot-jobs
  "Update the status of TDR snapshot jobs that are still 'running'."
  [source]
  (->> (get-pending-tdr-jobs source)
       (map #(update % 1 check-tdr-job))
       (run! #(write-snapshot-id source %))))

;; Create and add new snapshots to the snapshot queue
(defn ^:private update-tdr-source
  "Check for new data in TDR, create new snapshots, insert
   resulting job creation ids into database and update the
   timestamp for next time using transaction TX."
  [source]
  (find-and-snapshot-new-rows source (utc-now))
  (update-pending-snapshot-jobs source)
  ;; load and return the source table
  (jdbc/with-db-transaction [tx (postgres/wfl-db-config)]
    (load-tdr-source tx {:source_items (str (:id source))})))

(defn ^:private start-tdr-source [tx source]
  (update-last-checked tx source (utc-now)))

(defn ^:private peek-tdr-source-details
  "Get first unconsumed snapshot record from DETAILS table."
  [{:keys [details] :as _source}]
  (let [query "SELECT *
               FROM %s
               WHERE consumed IS NULL
               AND snapshot_id IS NOT NULL
               ORDER BY id ASC LIMIT 1"]
    (jdbc/with-db-transaction [tx (postgres/wfl-db-config)]
      (->> (format query details)
           (jdbc/query tx)
           first))))

(defn ^:private peek-tdr-source-queue
  "Get first unconsumed snapshot from SOURCE queue."
  [source]
  (if-let [{:keys [snapshot_id] :as _record} (peek-tdr-source-details source)]
    (datarepo/snapshot snapshot_id)))

(defn ^:private pop-tdr-source-queue
  "Consume first unconsumed snapshot record in DETAILS table, or throw if none."
  [{:keys [details] :as source}]
  (if-let [{:keys [id] :as _record} (peek-tdr-source-details source)]
    (jdbc/with-db-transaction [tx (postgres/wfl-db-config)]
      (let [now (OffsetDateTime/now)]
        (jdbc/update! tx details {:consumed now
                                  :updated  now}
                      ["id = ?" id])))
    (throw (ex-info "No snapshots in queue" {:source source}))))

(defoverload create-source! tdr-source-name create-tdr-source)
(defoverload start-source!  tdr-source-type start-tdr-source)
(defoverload update-source! tdr-source-type update-tdr-source)
(defoverload load-source!   tdr-source-type load-tdr-source)
(defoverload peek-queue!    tdr-source-type peek-tdr-source-queue)
(defoverload pop-queue!     tdr-source-type pop-tdr-source-queue)

;; Terra Executor
(def ^:private terra-executor-name  "Terra")
(def ^:private terra-executor-type  "TerraExecutor")
(def ^:private terra-executor-table "TerraExecutor")
(def ^:private terra-executor-serialized-fields
  {:workspace                  :workspace
   :methodConfiguration        :method_configuration
   :methodConfigurationVersion :method_configuration_version
   :fromSource                 :from_source})

(defn ^:private create-terra-executor [tx id request]
  (let [create  "CREATE TABLE %s OF TerraExecutorDetails (PRIMARY KEY (id))"
        alter   "ALTER TABLE %s ALTER COLUMN id ADD GENERATED ALWAYS AS IDENTITY"
        details (format "TerraExecutorDetails_%09d" id)
        _       (jdbc/db-do-commands tx [(format create details)
                                         (format alter details)])
        items   (-> (select-keys request (keys terra-executor-serialized-fields))
                    (update :fromSource pr-str)
                    (set/rename-keys terra-executor-serialized-fields)
                    (assoc :details details)
                    (->> (jdbc/insert! tx terra-executor-table)))]
    [terra-executor-type (-> items first :id str)]))

(defn ^:private load-terra-executor [tx {:keys [executor_items] :as workload}]
  (if-let [id (util/parse-int executor_items)]
    (-> (load-record-by-id! tx terra-executor-table id)
        (assoc :type terra-executor-type)
        (set/rename-keys (set/map-invert terra-executor-serialized-fields))
        (update :fromSource edn/read-string))
    (throw (ex-info "Invalid executor_items" {:workload workload}))))

(defn ^:private import-snapshot!
  "Return snapshot reference for ID imported to WORKSPACE as NAME."
  [{:keys [workspace] :as _executor}
   {:keys [name id]   :as _snapshot}]
  (rawls/create-snapshot-reference workspace id name))

(defn ^:private from-source
  "Coerce SOURCE-ITEM to form understood by EXECUTOR via FROMSOURCE."
  [{:keys [fromSource] :as executor}
   source-item]
  (cond (= "importSnapshot" fromSource) (import-snapshot! executor source-item)
        :else (throw (ex-info "Unknown fromSource" {:executor executor}))))

(defn ^:private create-submission!
  "TO IMPLEMENT:
  Create submission from REFERENCE."
  [executor reference])

(defn ^:private write-workflows!
  "Write WORKFLOWS to DETAILS table."
  [{:keys [details]                :as _executor}
   {:keys [referenceId]            :as _reference}
   {:keys [submissionId workflows] :as _submission}]
  (jdbc/with-db-transaction [tx (postgres/wfl-db-config)]
    (let [now     (OffsetDateTime/now)
          to-rows (fn [{:keys [status workflowId] :as _workflow}]
                    {:snapshot_reference_id referenceId
                     :rawls_submission_id   submissionId
                     :workflow_id           workflowId
                     :workflow_status       status
                     :updated               now})]
      (jdbc/insert-multi! tx details (map to-rows workflows)))))

(defn ^:private update-terra-workflow-statuses!
  "Update statuses in DETAILS table for active or failed WORKSPACE workflows."
  [{:keys [workspace details] :as _executor}]
  (letfn [(read-active-or-failed-workflows
            []
            (let [query "SELECT *
                         FROM %s
                         WHERE rawls_submission_id IS NOT NULL
                         AND workflow_id IS NOT NULL
                         AND workflow_status NOT IN ('Succeeded', 'Aborted')"]
              (jdbc/with-db-transaction [tx (postgres/wfl-db-config)]
                (jdbc/query tx (format query details)))))
          (update-workflow-status
            [{:keys [rawls_submission_id workflow_id] :as record}]
            (let [{:keys [status] :as _workflow}
                  (firecloud/get-workflow workspace
                                          rawls_submission_id
                                          workflow_id)]
              (assoc record :workflow_status status)))
          (write-workflow-statuses
            [now records]
            (jdbc/with-db-transaction [tx (postgres/wfl-db-config)]
              (-> (fn [{:keys [id workflow_status] :as _record}]
                    (jdbc/update! tx details
                                  {:workflow_status workflow_status
                                   :updated         now}
                                  ["id = ?" id]))
                  (run! records))))]
    (->> (read-active-or-failed-workflows)
         (map update-workflow-status)
         (write-workflow-statuses (OffsetDateTime/now)))))

(defn ^:private update-terra-executor
  "Create new submission from new SOURCE snapshot if available,
  writing its workflows to DETAILS table.
  Update statuses for active or failed workflows in DETAILS table.
  Return EXECUTOR."
  [source executor]
  (if-let [snapshot (peek-queue! source)]
    (let [reference  (from-source executor snapshot)
          submission (create-submission! executor reference)]
      (write-workflows! executor reference submission)
      (pop-queue! source)))
  (update-terra-workflow-statuses! executor)
  executor)

(defn ^:private peek-terra-executor-details
  "Get first unconsumed successful workflow record from DETAILS table."
  [{:keys [details] :as _executor}]
  (let [query "SELECT *
               FROM %s
               WHERE consumed IS NULL
               AND workflow_status = 'Succeeded'
               ORDER BY id ASC LIMIT 1"]
    (jdbc/with-db-transaction [tx (postgres/wfl-db-config)]
      (->> (format query details)
           (jdbc/query tx)
           first))))

(defn ^:private peek-terra-executor-queue
  "Get first unconsumed successful workflow from EXECUTOR queue."
  [{:keys [workspace] :as executor}]
  (if-let [{:keys [rawls_submission_id workflow_id] :as _record}
           (peek-terra-executor-details executor)]
    (firecloud/get-workflow workspace rawls_submission_id workflow_id)))

(defn ^:private pop-terra-executor-queue
  "Consume first unconsumed successful workflow record in DETAILS table,
  or throw if none."
  [{:keys [details] :as executor}]
  (if-let [{:keys [id] :as _record} (peek-terra-executor-details executor)]
    (jdbc/with-db-transaction [tx (postgres/wfl-db-config)]
      (let [now (OffsetDateTime/now)]
        (jdbc/update! tx details {:consumed now
                                  :updated  now}
                      ["id = ?" id])))
    (throw (ex-info "No successful workflows in queue" {:executor executor}))))

(defn ^:private terra-executor-workflows
  [tx {:keys [workspace details] :as _executor}]
  (when-not (postgres/table-exists? tx details)
    (throw (ex-info "Missing executor details table" {:table details})))
  (->> (format "SELECT DISTINCT rawls_submission_id FROM %s" details)
       (jdbc/query tx)
       (mapcat (comp :workflows #(firecloud/get-submission workspace %)))))

(defoverload create-executor!   terra-executor-name create-terra-executor)
(defoverload update-executor!   terra-executor-type update-terra-executor)
(defoverload load-executor!     terra-executor-type load-terra-executor)
(defoverload peek-queue!        terra-executor-type peek-terra-executor-queue)
(defoverload pop-queue!         terra-executor-type pop-terra-executor-queue)
(defoverload executor-workflows terra-executor-type terra-executor-workflows)

;; Terra Workspace Sink
(def ^:private terra-workspace-sink-name  "Terra Workspace")
(def ^:private terra-workspace-sink-type  "TerraWorkspaceSink")
(def ^:private terra-workspace-sink-table "TerraWorkspaceSink")
(def ^:private terra-workspace-sink-serialized-fields
  {:workspace   :workspace
   :entity      :entity
   :fromOutputs :from_outputs
   :identifier  :identifier})

(defn ^:private create-terra-workspace-sink [tx id request]
  (let [create  "CREATE TABLE %s OF TerraWorkspaceSinkDetails (PRIMARY KEY (id))"
        alter   "ALTER TABLE %s ALTER COLUMN id ADD GENERATED ALWAYS AS IDENTITY"
        details (format "TerraWorkspaceSinkDetails_%09d" id)
        _       (jdbc/db-do-commands tx [(format create details)
                                         (format alter details)])
        items   (-> (select-keys request (keys terra-workspace-sink-serialized-fields))
                    (update :fromOutputs pr-str)
                    (set/rename-keys terra-workspace-sink-serialized-fields)
                    (assoc :details details)
                    (->> (jdbc/insert! tx terra-workspace-sink-table)))]
    [terra-workspace-sink-type (-> items first :id str)]))

(defn ^:private load-terra-workspace-sink [tx {:keys [sink_items] :as workload}]
  (if-let [id (util/parse-int sink_items)]
    (-> (load-record-by-id! tx terra-workspace-sink-table id)
        (set/rename-keys (set/map-invert terra-workspace-sink-serialized-fields))
        (update :fromOutputs edn/read-string)
        (assoc :type terra-workspace-sink-type))
    (throw (ex-info "Invalid sink_items" {:workload workload}))))

;; visible for testing
(defn rename-gather
  "Transform the `values` using the transformation defined in `mapping`."
  [values mapping]
  (letfn [(literal? [x] (str/starts-with? x "$"))
          (go! [v]
            (cond (literal? v) (subs v 1 (count v))
                  (string?  v) (values (keyword v))
                  (map?     v) (rename-gather values v)
                  (coll?    v) (keep go! v)
                  :else        (throw (ex-info "Unknown operation"
                                               {:operation v}))))]
    (into {} (for [[k v] mapping] [k (go! v)]))))

(defn ^:private terra-workspace-sink-to-attributes
  [{:keys [outputs] :as workflow} fromOutputs]
  (when-not (map? fromOutputs)
    (throw (IllegalStateException. "fromOutputs is malformed")))
  (try
    (rename-gather outputs fromOutputs)
    (catch Exception cause
      (throw (ex-info "Failed to coerce workflow outputs to attribute values"
                      {:fromOutputs fromOutputs :workflow workflow}
                      cause)))))

(defn ^:private update-terra-workspace-sink
  [executor {:keys [fromOutputs workspace entity identifier details] :as _sink}]
  (when-let [{:keys [uuid outputs] :as workflow} (peek-queue! executor)]
    (log/debug "Coercing workflow" uuid "outputs to" entity)
    (let [attributes (terra-workspace-sink-to-attributes workflow fromOutputs)
          name       (outputs (keyword identifier))]
      (log/debug "Upserting workflow" uuid "outputs as" name)
      (rawls/batch-upsert workspace [[name entity attributes]])
      (pop-queue! executor)
      (log/info "Sank workflow" uuid "as" name)
      (jdbc/with-db-transaction [tx (postgres/wfl-db-config)]
        (->> {:entity_name name
              :workflow    uuid
              :updated     (OffsetDateTime/now)}
             (jdbc/insert! tx details))))))

(defoverload create-sink! terra-workspace-sink-name create-terra-workspace-sink)
(defoverload load-sink!   terra-workspace-sink-type load-terra-workspace-sink)
(defoverload update-sink! terra-workspace-sink-type update-terra-workspace-sink)

(defoverload workloads/create-workload!   pipeline create-covid-workload)
(defoverload workloads/start-workload!    pipeline start-covid-workload)
(defoverload workloads/update-workload!   pipeline update-covid-workload)
(defoverload workloads/stop-workload!     pipeline batch/stop-workload!)
(defoverload workloads/load-workload-impl pipeline load-covid-workload-impl)
(defmethod   workloads/workflows          pipeline
  [tx {:keys [executor] :as _workload}]
  (executor-workflows tx executor))

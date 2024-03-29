(ns wfl.source
  "Workload source interface and its implementations."
  (:require [clojure.edn          :as edn]
            [clojure.instant      :as instant]
            [clojure.set          :as set]
            [clojure.spec.alpha   :as s]
            [clojure.string       :as str]
            [wfl.api.workloads    :refer [defoverload] :as workloads]
            [wfl.jdbc             :as jdbc]
            [wfl.log              :as log]
            [wfl.module.all       :as all]
            [wfl.service.datarepo :as datarepo]
            [wfl.service.postgres :as postgres]
            [wfl.service.slack    :as slack]
            [wfl.stage            :as stage]
            [wfl.util             :as util])
  (:import [clojure.lang ExceptionInfo]
           [java.sql Timestamp]
           [java.time OffsetDateTime ZoneId]
           [java.time.format DateTimeFormatter]
           [java.time.temporal ChronoUnit]
           [wfl.util UserException]))

;; specs
(s/def ::snapshotReaders (s/* util/email-address?))
(s/def ::snapshots (s/* ::all/uuid))
(s/def ::pollingIntervalMinutes int?)
(s/def ::tdr-source
  (s/keys :req-un [::all/name
                   ::all/dataset
                   ::all/table
                   ::snapshotReaders]
          :opt-un [::snapshots
                   ::pollingIntervalMinutes
                   ::datarepo/loadTag]))

(s/def ::snapshot-list-source
  (s/keys :req-un [::all/name ::snapshots]))

(s/def ::source (s/or :dataset   ::tdr-source
                      :snapshots ::snapshot-list-source))

;; `source` "interface"
(defmulti start-source!
  "Start enqueuing items onto the `source`'s queue to be consumed by a later
   processing stage. This operation should not perform any long-running
   external effects other than database operations via the `transaction`. This
   function is called at most once during a workload's operation."
  (fn [_transaction source] (:type source)))

(defmulti stop-source!
  "Stop enqueuing inputs onto the `source`'s queue to be consumed by a later
   processing stage. This operation should not perform any long-running
   external effects other than database operations via the `transaction`. This
   function is called at most once during a workload's operation and will only
   be called after `start-source!`. Any outstanding items on the `source`
   queue may still be consumed by a later processing stage."
  (fn [_transaction source] (:type source)))

(defmulti update-source!
  "Enqueue items onto the `workload`'s source queue to be consumed by a
  later processing stage unless stopped, performing any external
  effects as necessary. Implementations should avoid maintaining
  in-memory state and making long- running external calls, favouring
  internal queues to manage such tasks asynchronously between
  invocations. This function is called one or more times after
  `start-source!` and may be called after `stop-source!`"
  (fn [{:keys [source] :as _workload}] (:type source)))

;; source load/save operations
(defmulti create-source!
  "Create a `Source` instance using the database `transaction` and configuration
   in the source `request` and return a `[type items]` pair to be written to a
   workload record as `source_type` and `source_items`.
   Notes:
   - This is a factory method registered for workload creation.
   - The `Source` type string must match a value of the `source` enum in the
     database schema.
   - This multimethod is type-dispatched on the `:name` association in the
     `request`."
  (fn [_transaction _id source-request] (:name source-request)))

(defmulti load-source!
  "Return the `Source` implementation associated with the `source_type` and
   `source_items` fields of the `workload` row in the database. Note that this
   multimethod is type-dispatched on the `:source_type` association in the
   `workload`."
  (fn [_transaction workload] (:source_type workload)))

(defmethod create-source! :default
  [_ _ {:keys [name] :as request}]
  (throw (UserException.
          "Invalid source name"
          {:name    name
           :request request
           :status  400
           :sources (-> create-source! methods (dissoc :default) keys)})))

;; Terra Data Repository Source
(def ^:private ^:const tdr-source-name  "Terra DataRepo")
(def ^:private ^:const tdr-source-type  "TerraDataRepoSource")
(def ^:private ^:const tdr-source-table "TerraDataRepoSource")
(def ^:private ^:const tdr-source-serialized-fields
  {:dataset                :dataset
   :table                  :dataset_table
   :snapshotReaders        :snapshot_readers
   :pollingIntervalMinutes :polling_interval_minutes
   :loadTag                :load_tag})

(def ^:private bigquery-datetime-format
  (DateTimeFormatter/ofPattern "yyyy-MM-dd'T'HH:mm:ss"))

(defn ^:private timestamp-to-offsetdatetime
  "Parse the Timestamp `t` into an `OffsetDateTime`."
  [^Timestamp t]
  (OffsetDateTime/ofInstant (.toInstant t) (ZoneId/of "UTC")))

(defn ^:private write-tdr-source [tx id source]
  (let [create  "CREATE TABLE %s OF TerraDataRepoSourceDetails (PRIMARY KEY (id))"
        alter   "ALTER TABLE %s ALTER COLUMN id ADD GENERATED ALWAYS AS IDENTITY"
        details (format "%s_%09d" tdr-source-type id)]
    (jdbc/db-do-commands tx [(format create details) (format alter details)])
    [tdr-source-type
     (-> (select-keys source (keys tdr-source-serialized-fields))
         (update :dataset pr-str)
         (set/rename-keys tdr-source-serialized-fields)
         (assoc :details details)
         (->> (jdbc/insert! tx tdr-source-table) first :id str))]))

(defn ^:private load-tdr-source [tx {:keys [source_items] :as workload}]
  (if-let [id (util/parse-int source_items)]
    (-> (postgres/load-record-by-id! tx tdr-source-table id)
        (set/rename-keys (set/map-invert tdr-source-serialized-fields))
        (assoc :type tdr-source-type)
        (update :dataset edn/read-string))
    (throw (ex-info "source_items is not an integer" {:workload workload}))))

(defn datarepo-source-validate-request-or-throw
  "Verify that the `dataset` exists and that WFL has the necessary permissions
   to read it."
  [{:keys [dataset table skipValidation] :as source}]
  (if skipValidation
    (assoc source :dataset {:id (get source :dataset)})
    (let [dataset (datarepo/datasets dataset)]
      (datarepo/table-or-throw table dataset)
      (datarepo/metadata-table-path-or-throw table dataset)
      (assoc source :dataset dataset))))

(defn ^:private filter-and-combine-tdr-source-details
  "Reduce TerraDataRepoSourceDetails snapshot creation job `records`
   to a single result with all previously processed row IDs,
   the earliest snapshot creation job start time,
   and the latest snapshot creation job end time."
  [records]
  (letfn [(running-or-succeeded?
            [{:keys [snapshot_creation_job_status] :as _record}]
            (#{"running" "succeeded"} snapshot_creation_job_status))
          (combine-records
            [result {:keys [datarepo_row_ids start_time end_time] :as _record}]
            (-> result
                (update :datarepo_row_ids into          datarepo_row_ids)
                (update :start_time       util/earliest start_time)
                (update :end_time         util/latest   end_time)))]
    (let [filtered (filter running-or-succeeded? records)]
      (when (seq filtered)
        (reduce combine-records filtered)))))

(defn ^:private find-new-rows
  "Query TDR for rows within `interval` that are new to `source`."
  [{{:keys [dataset details table loadTag]} :source :as workload}
   [begin end                                       :as interval]]
  (log/info "Looking for new rows in row metadata table"
            :workload (workloads/to-log workload)
            :dataset  (:name dataset)
            :table    table
            :interval interval)
  (let [wfl     (jdbc/with-db-transaction [tx (postgres/wfl-db-config)]
                  (postgres/get-table tx details))
        old     (filter-and-combine-tdr-source-details wfl)
        start   (if-let [start_time (:start_time old)]
                  (-> start_time
                      (util/latest (instant/read-instant-timestamp begin))
                      timestamp-to-offsetdatetime
                      (.format bigquery-datetime-format))
                  begin)
        filters   {:ingestTime [start end]
                   :loadTag    loadTag}
        tdr     (-> (datarepo/query-metadata-table
                     dataset table filters [:datarepo_row_id])
                    :rows
                    flatten)]
    (when (seq tdr) (remove (set (:datarepo_row_ids old)) tdr))))

(defn ^:private go-create-snapshot!
  "Create snapshot in TDR from `dataset` body, `table` and `row-ids` then
   write job info as well as rows into `source-details-name` table.
   Snapshots will be readable by members of the `snapshotReaders` list.
   `suffix` will be appended to the snapshot names."
  [suffix
   {{:keys [dataset table snapshotReaders]} :source :as _workload}
   row-ids]
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
  [workload now-obj row-ids]
  (let [dt-format   (DateTimeFormatter/ofPattern "YYYYMMdd'T'HHmmss")
        compact-now (.format now-obj dt-format)]
    (letfn [(create-snapshot [idx shard]
              [shard (go-create-snapshot! (format "_%s_%s" compact-now idx)
                                          workload shard)])]
      (->> row-ids
           (partition-all 500)
           (map vec)
           (map-indexed create-snapshot)))))

(defn ^:private get-pending-tdr-jobs
  "Return the IDs of pending TDR snapshot creation jobs for `_source.`"
  [{:keys [source] :as _workload}]
  (let [query (str/join \space ["SELECT id, snapshot_creation_job_id FROM %s"
                                "WHERE snapshot_creation_job_status = 'running'"
                                "ORDER BY id ASC"])]
    (jdbc/with-db-transaction [tx (postgres/wfl-db-config)]
      (->> (format query (:details source))
           (jdbc/query tx)
           (map (juxt :id :snapshot_creation_job_id))))))

(defn ^:private result-or-catch
  "Return the result of `callable`,
  or the status and parsed response body of its thrown exception."
  [callable]
  (try
    (callable)
    (catch ExceptionInfo caught
      (let [{:keys [status] :as data} (ex-data caught)]
        {:status status
         :body   (util/response-body-json data)}))))

(defn ^:private tdr-job-failed-slack-msg
  "Return a mrkdwn Slack message to be emitted for failed TDR jobs."
  [{:keys [id job_status] :as _metadata}
   {:keys [status body]   :as _caught-job-result}]
  (str/join \newline
            [(format "*:sadpanda: Snapshot creation job %s %s*" id job_status)
             (format "`%s: %s`" status (:message body))]))

(defn ^:private check-tdr-job-and-notify-on-failure
  "Check TDR job status for `job-id` and return job metadata,
   with snapshot_id attached if the job succeeded.
   Notify `workload` watchers if the job failed."
  [job-id workload]
  (let [{:keys [job_status] :as metadata} (datarepo/job-metadata job-id)
        get-job-result                    #(datarepo/job-result job-id)]
    (case job_status
      "running"   metadata
      "succeeded" (assoc metadata :snapshot_id (:id (get-job-result)))
      (let [caught  (result-or-catch get-job-result)
            message (tdr-job-failed-slack-msg metadata caught)]
        (log/warning "TDR job failed or has otherwise unknown status"
                     :workload   (workloads/to-log workload)
                     :metadata   metadata
                     :job-result caught)
        (slack/notify-watchers workload message)
        metadata))))

(defn ^:private write-snapshot-id
  "Write `snapshot_id` and `job_status` into `source` details table
   from the `_tdr-job-metadata` map, update timestamp with real now."
  [{:keys [source]                     :as _workload}
   [id {:keys [job_status snapshot_id] :as _tdr-job-metadata}]]
  (jdbc/with-db-transaction [tx (postgres/wfl-db-config)]
    (jdbc/update! tx (:details source)
                  {:snapshot_creation_job_status job_status
                   :snapshot_id                  snapshot_id
                   :updated                      (util/utc-now)}
                  ["id = ?" id])))

(defn ^:private write-snapshots-creation-jobs
  "Write the shards and corresponding snapshot creation jobs from
   `shards->snapshot-jobs` into source `details` table, with the frozen `now`.
   Also initialize all jobs statuses to running."
  [{{:keys [last_checked details]} :source :as workload}
   now
   shards->snapshot-jobs]
  (letfn [(make-record [[shard id]]
            {:snapshot_creation_job_id     id
             :snapshot_creation_job_status "running"
             :datarepo_row_ids             shard
             :start_time                   last_checked
             :end_time                     now})]
    (jdbc/with-db-transaction [tx (postgres/wfl-db-config)]
      (->> shards->snapshot-jobs
           (map make-record)
           (jdbc/insert-multi! tx details)))
    (log/debug "Snapshot creation jobs written."
               :workload (workloads/to-log workload))))

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
  [{{:keys [dataset table last_checked] :as source} :source :as workload}
   now]
  (let [checked      (timestamp-to-offsetdatetime last_checked)
        hours-ago    (* 2 (max 1 (.between ChronoUnit/HOURS checked now)))
        then         (.minusHours now hours-ago)
        shards->jobs (->> [then now]
                          (mapv #(.format % bigquery-datetime-format))
                          (find-new-rows workload)
                          (create-snapshots workload now))]
    (when (seq shards->jobs)
      (log/info "Snapshots created from new rows."
                :workload (workloads/to-log workload)
                :dataset  (:name dataset)
                :table    table)
      (write-snapshots-creation-jobs workload now shards->jobs))
    ;; Even if our poll did not yield new rows to snapshot, at least we tried:
    (update-last-checked source now)))

(defn ^:private update-pending-snapshot-jobs
  "Update the status of 'running' TDR snapshots in `source`."
  [workload]
  (log/debug "Looking for running snapshot jobs."
             :workload (workloads/to-log workload))
  (let [pending-tdr-jobs (get-pending-tdr-jobs workload)]
    (when (seq pending-tdr-jobs)
      (->> pending-tdr-jobs
           (map #(update % 1 check-tdr-job-and-notify-on-failure workload))
           (run! #(write-snapshot-id workload %)))
      (log/debug "Running snapshot jobs updated."
                 :workload (workloads/to-log workload)))))

;; Workloads in general may be updated more frequently,
;; but overpolling the TDR increases the chances of:
;; - creating many single-row / low-cardinality snapshots
;; - locking the dataset / running into dataset locks
;;
(def ^:private tdr-source-default-polling-interval-minutes 20)

(defn ^:private tdr-source-should-poll?
  "Return true if it's been at least the specified `polling_interval_minutes`
   or, if unspecified, `tdr-source-default-polling-interval-minutes`
   since `last_checked` -- when we last checked for new rows in the TDR."
  [{{:keys [last_checked pollingIntervalMinutes]} :source :as workload} now]
  (let [checked            (timestamp-to-offsetdatetime last_checked)
        minutes-since-poll (.between ChronoUnit/MINUTES checked now)
        polling-interval   (or pollingIntervalMinutes
                               tdr-source-default-polling-interval-minutes)]
    (log/debug "Checking if we should poll TDR for new rows."
               :workload           (workloads/to-log workload)
               :minutes-since-poll minutes-since-poll
               :polling-interval   polling-interval)
    (<= polling-interval minutes-since-poll)))

(defn ^:private update-tdr-source
  "Check for new data in TDR from `workload`'s `source`,
  create new snapshots,
  insert resulting job creation ids into database,
  and update the timestamp for next time."
  [{:keys [source] :as workload}]
  (let [now (util/utc-now)]
    (when (and (not (:stopped source))
               (tdr-source-should-poll? workload now))
      (find-and-snapshot-new-rows workload now)))
  (update-pending-snapshot-jobs workload)
  ;; load and return the workload with the updated source
  (jdbc/with-db-transaction [tx (postgres/wfl-db-config)]
    (workloads/load-workload-for-uuid tx (:uuid workload))))

(defn ^:private start-tdr-source [tx source]
  (update-last-checked tx source (util/utc-now)))

(defn ^:private stop-tdr-source [tx {:keys [id] :as _source}]
  (jdbc/update! tx tdr-source-table {:stopped (util/utc-now)} ["id = ?" id]))

(defn ^:private peek-tdr-source-details
  "Get first unconsumed snapshot record from `details` table."
  [{:keys [details] :as _source}]
  (let [query (str/join \space ["SELECT * FROM %s"
                                "WHERE consumed IS NULL"
                                "AND snapshot_id IS NOT NULL"
                                "ORDER BY id ASC"
                                "LIMIT 1"])]
    (jdbc/with-db-transaction [tx (postgres/wfl-db-config)]
      (->> (format query details)
           (jdbc/query tx)
           first))))

(defn ^:private peek-tdr-source-queue
  "Get first unconsumed snapshot from `source` queue."
  [source]
  (when-let [{:keys [snapshot_id] :as _record} (peek-tdr-source-details source)]
    [:datarepo/snapshot (datarepo/snapshot snapshot_id)]))

(defn ^:private tdr-source-queue-length
  "Return the number of unconsumed snapshot records from `details` table."
  [{:keys [details] :as _source}]
  (let [query (str/join \space ["SELECT COUNT(*) FROM %s"
                                "WHERE consumed IS NULL"
                                "AND snapshot_creation_job_status <> 'failed'"])]
    (jdbc/with-db-transaction [tx (postgres/wfl-db-config)]
      (->> (format query details)
           (jdbc/query tx)
           first
           :count))))

(defn ^:private pop-tdr-source-queue
  "Consume first unconsumed snapshot record in `details` table, or throw if none."
  [{:keys [details] :as source}]
  (if-let [{:keys [id] :as _record} (peek-tdr-source-details source)]
    (jdbc/with-db-transaction [tx (postgres/wfl-db-config)]
      (let [now (util/utc-now)]
        (jdbc/update! tx details {:consumed now :updated now} ["id = ?" id])))
    (throw (ex-info "No snapshots in queue" {:source source}))))

(defn ^:private tdr-source-done? [{:keys [stopped] :as source}]
  (and stopped (zero? (stage/queue-length source))))

(defn ^:private tdr-source-to-edn [source]
  (-> source
      (util/select-non-nil-keys (keys tdr-source-serialized-fields))
      (update :dataset :id)
      (assoc :name tdr-source-name)))

(defmethod create-source! tdr-source-name
  [tx id request]
  (write-tdr-source tx id (datarepo-source-validate-request-or-throw request)))

(defoverload load-source!   tdr-source-type load-tdr-source)
(defoverload start-source!  tdr-source-type start-tdr-source)
(defoverload update-source! tdr-source-type update-tdr-source)
(defoverload stop-source!   tdr-source-type stop-tdr-source)

(defoverload stage/peek-queue   tdr-source-type peek-tdr-source-queue)
(defoverload stage/pop-queue!   tdr-source-type pop-tdr-source-queue)
(defoverload stage/queue-length tdr-source-type tdr-source-queue-length)
(defoverload stage/done?        tdr-source-type tdr-source-done?)

(defoverload util/to-edn tdr-source-type tdr-source-to-edn)

;; TDR Snapshot List Source
(def ^:private ^:const tdr-snapshot-list-name "TDR Snapshots")
(def ^:private ^:const tdr-snapshot-list-type "TDRSnapshotListSource")

(defn tdr-snapshot-list-validate-request-or-throw
  [{:keys [skipValidation] :as source}]
  (letfn [(snapshot-or-throw [snapshot-id]
            (try
              (datarepo/snapshot snapshot-id)
              (catch ExceptionInfo e
                (throw (UserException. "Cannot access snapshot"
                                       {:snapshot snapshot-id
                                        :status   (-> e ex-data :status)}
                                       e)))))]
    (if skipValidation
      source
      (update source :snapshots #(mapv snapshot-or-throw %)))))

(defn ^:private write-tdr-snapshot-list [tx id {:keys [snapshots] :as _request}]
  (let [create  "CREATE TABLE %s OF ListSource (PRIMARY KEY (id))"
        alter   "ALTER TABLE %s ALTER COLUMN id ADD GENERATED ALWAYS AS IDENTITY"
        details (format "%s_%09d" tdr-snapshot-list-type id)]
    (jdbc/db-do-commands tx [(format create details) (format alter details)])
    (jdbc/insert-multi! tx details (map #(hash-map :item (pr-str %)) snapshots))
    [tdr-snapshot-list-type details]))

(defn ^:private load-tdr-snapshot-list
  [tx {:keys [source_items] :as _workload}]
  {:type      tdr-snapshot-list-type
   :items     source_items
   :snapshots (postgres/get-table tx source_items)})

(defn ^:private start-tdr-snapshot-list [_ source] source)
(defn ^:private stop-tdr-snapshot-list  [_ source] source)
(defn ^:private update-tdr-snapshot-list [workload]  workload)

(defn ^:private peek-tdr-snapshot-details-table [{:keys [items] :as _source}]
  (let [query (str/join \space ["SELECT * FROM %s"
                                "WHERE consumed IS NULL"
                                "ORDER BY id ASC"
                                "LIMIT 1"])]
    (jdbc/with-db-transaction [tx (postgres/wfl-db-config)]
      (->> (format query items)
           (jdbc/query tx)
           first))))

(defn ^:private peek-tdr-snapshot-list [source]
  (when-let [{:keys [item]} (peek-tdr-snapshot-details-table source)]
    [:datarepo/snapshot (edn/read-string item)]))

(defn ^:private tdr-snapshot-list-queue-length [{:keys [items] :as _source}]
  (let [query "SELECT COUNT (*) FROM %s WHERE consumed IS NULL"]
    (jdbc/with-db-transaction [tx (postgres/wfl-db-config)]
      (->> (format query items)
           (jdbc/query tx)
           first
           :count))))

(defn ^:private pop-tdr-snapshot-list [{:keys [items] :as source}]
  (if-let [{:keys [id]} (peek-tdr-snapshot-details-table source)]
    (jdbc/with-db-transaction [tx (postgres/wfl-db-config)]
      (jdbc/update! tx items {:consumed (util/utc-now)} ["id = ?" id]))
    (throw (ex-info "Attempt to pop empty queue" {:source source}))))

(defn ^:private tdr-snapshot-list-done? [source]
  (zero? (tdr-snapshot-list-queue-length source)))

(defn ^:private tdr-snapshot-list-to-edn [source]
  (let [read-snapshot-id (comp :id edn/read-string :item)]
    (-> (select-keys source [:snapshots])
        (assoc :name tdr-snapshot-list-name)
        (update :snapshots #(map read-snapshot-id %)))))

(defmethod create-source! tdr-snapshot-list-name
  [tx id request]
  (write-tdr-snapshot-list
   tx id (tdr-snapshot-list-validate-request-or-throw request)))

(defoverload load-source!   tdr-snapshot-list-type  load-tdr-snapshot-list)
(defoverload start-source!  tdr-snapshot-list-type  start-tdr-snapshot-list)
(defoverload stop-source!   tdr-snapshot-list-type  stop-tdr-snapshot-list)
(defoverload update-source! tdr-snapshot-list-type  update-tdr-snapshot-list)

(defoverload stage/peek-queue tdr-snapshot-list-type peek-tdr-snapshot-list)
(defoverload stage/pop-queue! tdr-snapshot-list-type pop-tdr-snapshot-list)
(defoverload stage/queue-length tdr-snapshot-list-type  tdr-snapshot-list-queue-length)
(defoverload stage/done? tdr-snapshot-list-type  tdr-snapshot-list-done?)

(defoverload util/to-edn tdr-snapshot-list-type tdr-snapshot-list-to-edn)

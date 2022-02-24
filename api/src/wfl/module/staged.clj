(ns wfl.module.staged
  "Manage staged (source -> executor -> sink) workloads."
  (:require [clojure.spec.alpha   :as s]
            [clojure.edn          :as edn]
            [wfl.api.workloads    :as workloads :refer [defoverload]]
            [wfl.executor         :as executor]
            [wfl.jdbc             :as jdbc]
            [wfl.module.all       :as all]
            [wfl.service.postgres :as postgres]
            [wfl.sink             :as sink]
            [wfl.source           :as source]
            [wfl.stage            :as stage]
            [wfl.util             :as util :refer [utc-now]]
            [wfl.wfl              :as wfl])
  (:import [java.util UUID]
           [wfl.util UserException]))

(def pipeline nil)

;; specs
(s/def ::creator util/email-address?)

(s/def ::workload-request (s/keys :req-un [::executor/executor
                                           ::all/project
                                           ::sink/sink
                                           ::source/source]
                                  :opt-un [::all/labels
                                           ::all/watchers]))

(s/def ::workload-response (s/keys :req-un [::all/created
                                            ::creator
                                            ::executor/executor
                                            ::all/labels
                                            ::sink/sink
                                            ::source/source
                                            ::all/uuid
                                            ::all/version]
                                   :opt-un [::all/finished
                                            ::all/started
                                            ::all/stopped
                                            ::all/updated
                                            ::all/watchers]))

;; Workload
(defn ^:private patch-workload [tx {:keys [id]} colls]
  (jdbc/update! tx :workload colls ["id = ?" id]))

(def ^:private workload-metadata-keys
  [:commit
   :created
   :creator
   :executor
   :finished
   :labels
   :sink
   :source
   :started
   :stopped
   :updated
   :uuid
   :version
   :watchers])

(defn ^:private add-workload-metadata
  "Use `tx` to record the workload metadata in `request` in the workload table
   and return the ID the of the new row."
  [tx {:keys [project] :as request}]
  (letfn [(combine-labels [labels]
            (->> (str "project:" project)
                 (conj labels)
                 set
                 sort
                 vec))]
    (-> (update request :labels combine-labels)
        (select-keys [:creator :watchers :labels :project])
        (update :watchers pr-str)
        (merge (select-keys (wfl/get-the-version) [:commit :version]))
        (assoc :executor ""
               :output   ""
               :release  ""
               :wdl      ""
               :uuid     (UUID/randomUUID))
        (->> (jdbc/insert! tx :workload) first :id))))

(def ^:private update-workload-query
  "UPDATE workload
   SET    source_type    = ?::source
   ,      source_items   = ?
   ,      executor_type  = ?::executor
   ,      executor_items = ?
   ,      sink_type      = ?::sink
   ,      sink_items     = ?
   WHERE  id = ?")

(defn ^:private create-staged-workload
  [tx {:keys [source executor sink] :as request}]
  (let [id (add-workload-metadata tx request)]
    (jdbc/execute!
     tx
     (concat [update-workload-query]
             (source/create-source! tx id source)
             (executor/create-executor! tx id executor)
             (sink/create-sink! tx id sink)
             [id]))
    (workloads/load-workload-for-id tx id)))

(defn ^:private load-staged-workload-impl [tx {:keys [id] :as workload}]
  (let [src-exc-sink {:source   (source/load-source! tx workload)
                      :executor (executor/load-executor! tx workload)
                      :sink     (sink/load-sink! tx workload)}]
    (as-> workload $
      (select-keys $ workload-metadata-keys)
      (update $ :watchers edn/read-string)
      (merge $ src-exc-sink)
      (filter second $)
      (into {:type :workload :id id} $))))

(defn ^:private start-staged-workload
  "Start creating and managing workflows from the source."
  [tx {:keys [started] :as workload}]
  (letfn [(start [{:keys [id source] :as workload} now]
            (source/start-source! tx source)
            (patch-workload tx workload {:started now :updated now})
            (workloads/load-workload-for-id tx id))]
    (if-not started (start workload (utc-now)) workload)))

(defn ^:private update-staged-workload
  "Use transaction `tx` to update `workload` statuses."
  [tx {:keys [started finished] :as workload}]
  (letfn [(update! [{:keys [id source executor sink] :as workload} now]
            (-> workload
                (source/update-source!)
                (executor/update-executor!)
                (sink/update-sink!))
            (patch-workload tx workload {:updated now})
            (when (every? stage/done? [source executor sink])
              (patch-workload tx workload {:finished now}))
            (workloads/load-workload-for-id tx id))]
    (if (and started (not finished)) (update! workload (utc-now)) workload)))

(defn ^:private stop-staged-workload
  "Use transaction `tx` to stop the `workload` looking for new data."
  [tx {:keys [started stopped finished] :as workload}]
  (letfn [(stop! [{:keys [id source] :as workload} now]
            (source/stop-source! tx source)
            (patch-workload tx workload {:stopped now :updated now})
            (when-not (:started workload)
              (patch-workload tx workload {:finished now}))
            (workloads/load-workload-for-id tx id))]
    (when-not started
      (throw (UserException. "Cannot stop workload before it's been started."
                             {:workload workload})))
    (if-not (or stopped finished) (stop! workload (utc-now)) workload)))

(defn ^:private retry-staged-workload
  "Retry/resubmit the `workflows` managed by the `workload` and return the
   workload that manages the new workflows."
  [{:keys [started id executor] :as workload} workflows]
  (when-not started
    (throw (UserException. "Cannot retry workload before it's been started."
                           {:workload workload})))
  ;; TODO: validate workload's executor and sink objects.
  ;; https://broadinstitute.atlassian.net/browse/GH-1421
  (executor/executor-retry-workflows! workload workflows)
  (jdbc/with-db-transaction [tx (postgres/wfl-db-config)]
    (when-not (stage/done? executor)
      (patch-workload tx workload {:finished nil :updated (utc-now)}))
    (workloads/load-workload-for-id tx id)))

(defn ^:private workload-to-edn [workload]
  (-> workload
      (util/select-non-nil-keys workload-metadata-keys)
      (dissoc :pipeline)
      (update :source   util/to-edn)
      (update :executor util/to-edn)
      (update :sink     util/to-edn)))

;; Handle `workload` as a raw DB record
;; (source, executor, and sink not populated)
;; or a loaded workload object.
;;
(defn ^:private workload-to-log [workload]
  (-> workload
      (select-keys [:uuid :source :executor :sink :labels])
      (update :source   #(or (stage/to-log %) (:source_type workload)))
      (update :executor #(or (stage/to-log %) (:executor_type workload)))
      (update :sink     #(or (stage/to-log %) (:sink_type workload)))))

(defoverload workloads/create-workload!     pipeline create-staged-workload)
(defoverload workloads/start-workload!      pipeline start-staged-workload)
(defoverload workloads/update-workload!     pipeline update-staged-workload)
(defoverload workloads/stop-workload!       pipeline stop-staged-workload)
(defoverload workloads/load-workload-impl   pipeline load-staged-workload-impl)
(defmethod   workloads/workflows            pipeline
  [tx {:keys [executor] :as _workload}]
  (executor/executor-workflows tx executor {}))
(defmethod   workloads/workflows-by-filters pipeline
  [tx {:keys [executor] :as _workload} filters]
  (executor/executor-workflows tx executor filters))
(defoverload workloads/throw-if-invalid-retry-filters
  pipeline executor/executor-throw-if-invalid-retry-filters)
(defoverload workloads/retry               pipeline retry-staged-workload)
(defoverload workloads/to-edn              pipeline workload-to-edn)
(defoverload workloads/to-log              pipeline workload-to-log)

(ns wfl.module.covid
  "Manage the Sarscov2IlluminaFull pipeline."
  (:require [wfl.api.workloads :as workloads :refer [defoverload]]
            [clojure.spec.alpha :as s]
            [wfl.executor :as executor]
            [wfl.jdbc :as jdbc]
            [wfl.module.batch :as batch]
            [wfl.sink :as sink]
            [wfl.source :as source]
            [wfl.stage :as stage]
            [wfl.util :as util :refer [utc-now]]
            [wfl.wfl :as wfl]
            [wfl.module.all :as all])
  (:import [java.util UUID]
           [wfl.util UserException]))

(def pipeline nil)

;; specs
(s/def ::executor (s/keys :req-un [::all/name
                                   ::all/fromSource
                                   ::all/methodConfiguration
                                   ::all/methodConfigurationVersion
                                   ::all/workspace]))
(s/def ::creator util/email-address?)
(s/def ::workload-request (s/keys :req-un [::executor
                                           ::all/project
                                           ::all/sink
                                           ::source/source]
                                  :opt-un [::all/labels
                                           ::all/watchers]))

(s/def ::workload-response (s/keys :req-un [::all/created
                                            ::creator
                                            ::executor
                                            ::all/labels
                                            ::all/sink
                                            ::source/source
                                            ::all/uuid
                                            ::all/version
                                            ::all/watchers]
                                   :opt-un [::all/finished
                                            ::all/started
                                            ::all/stopped
                                            ::all/updated]))
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

(defn ^:private create-covid-workload
  [tx {:keys [source executor sink] :as request}]
  (let [[source executor sink] (mapv stage/validate-or-throw [source executor sink])
        id                     (add-workload-metadata tx request)]
    (jdbc/execute!
     tx
     (concat [update-workload-query]
             (source/create-source! tx id source)
             (executor/create-executor! tx id executor)
             (sink/create-sink! tx id sink)
             [id]))
    (workloads/load-workload-for-id tx id)))

(defn ^:private load-covid-workload-impl [tx {:keys [id] :as workload}]
  (let [src-exc-sink {:source   (source/load-source! tx workload)
                      :executor (executor/load-executor! tx workload)
                      :sink     (sink/load-sink! tx workload)}]
    (as-> workload $
      (select-keys $ workload-metadata-keys)
      (merge $ src-exc-sink)
      (filter second $)
      (into {:type :workload :id id} $))))

(defn ^:private start-covid-workload
  "Start creating and managing workflows from the source."
  [tx {:keys [started] :as workload}]
  (letfn [(start [{:keys [id source] :as workload} now]
            (source/start-source! tx source)
            (patch-workload tx workload {:started now :updated now})
            (workloads/load-workload-for-id tx id))]
    (if-not started (start workload (utc-now)) workload)))

(defn ^:private update-covid-workload
  "Use transaction `tx` to update `workload` statuses."
  [tx {:keys [started finished] :as workload}]
  (letfn [(update! [{:keys [id source executor sink] :as workload} now]
            (-> (source/update-source! source)
                (executor/update-executor! executor)
                (sink/update-sink! sink))
            (patch-workload tx workload {:updated now})
            (when (every? stage/done? [source executor sink])
              (patch-workload tx workload {:finished now}))
            (workloads/load-workload-for-id tx id))]
    (if (and started (not finished)) (update! workload (utc-now)) workload)))

(defn ^:private stop-covid-workload
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

(defn ^:private workload-to-edn [workload]
  (-> workload
      (util/select-non-nil-keys workload-metadata-keys)
      (dissoc :pipeline)
      (update :source   util/to-edn)
      (update :executor util/to-edn)
      (update :sink     util/to-edn)))

(defoverload workloads/create-workload!    pipeline create-covid-workload)
(defoverload workloads/start-workload!     pipeline start-covid-workload)
(defoverload workloads/update-workload!    pipeline update-covid-workload)
(defoverload workloads/stop-workload!      pipeline stop-covid-workload)
(defoverload workloads/retry               pipeline batch/retry-unsupported)
(defoverload workloads/load-workload-impl  pipeline load-covid-workload-impl)
(defmethod   workloads/workflows           pipeline
  [tx {:keys [executor] :as _workload}]
  (executor/executor-workflows tx executor))
(defmethod   workloads/workflows-by-status pipeline
  [tx {:keys [executor] :as _workload} status]
  (executor/executor-workflows-by-status tx executor status))
(defoverload workloads/to-edn             pipeline workload-to-edn)

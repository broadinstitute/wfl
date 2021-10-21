(ns wfl.api.workloads
  (:require [clojure.string :as str]
            [wfl.log        :as log]
            [wfl.jdbc       :as jdbc]
            [wfl.util       :as util])
  (:import [wfl.util UserException]))

;; always derive from base :wfl/exception
(derive ::invalid-pipeline :wfl/exception)
(derive ::workload-not-found :wfl/exception)

;; creating and dispatching workloads to cromwell
(defmulti create-workload!
  "(transaction workload-request) -> workload"
  (fn [_transaction request] (:pipeline request)))

(defmulti start-workload!
  "(transaction workload) -> workload"
  (fn [_transaction workload] (:pipeline workload)))

(defmulti stop-workload!
  "(transaction workload) -> workload"
  (fn [_transaction workload] (:pipeline workload)))

(defmulti execute-workload!
  "(transaction workload) -> workload"
  (fn [_transaction workload] (:pipeline workload)))

(defmulti update-workload!
  "(transaction workload) -> workload"
  (fn [_transaction workload] (:pipeline workload)))

(defmulti workflows
  "Use db `transaction` to return the workflows managed by the `workload`."
  (fn [_transaction workload] (:pipeline workload)))

(defmulti workflows-by-filters
  "Use db `transaction` to return the workflows managed by the `workload`
   matching `filters` (ex. status, submission)."
  (fn [_transaction workload _filters] (:pipeline workload)))

(defmulti throw-if-invalid-retry-filters
  "Throw if `filters` are invalid for `workload`'s retry request."
  (fn [workload _filters] (:pipeline workload)))

(defmulti retry
  "Retry/resubmit the `workflows` managed by the `workload` and return the
   workload that manages the new workflows."
  (fn [workload _workflows] (:pipeline workload)))

(defmulti to-edn
  "Return an EDN representation of the `workload` that will be shown to users."
  :pipeline)

;; loading utilities
(defmulti load-workload-impl
  "Load the workload given a TRANSACTION and a partially loaded WORKLOAD.
  NOTE: do NOT call directly in product code - this is only meant to be called
  within this namespace."
  (fn [_transaction body] (:pipeline body)))

(defn ^:private try-load-workload-impl [tx workload]
  (try
    (load-workload-impl tx workload)
    (catch Throwable cause
      (throw (ex-info (str "Error loading workload - " (.getMessage cause))
                      (assoc (ex-data cause) :workload workload)
                      cause)))))

(defn load-workload-for-uuid
  "Use transaction `tx` to load `workload` with `uuid`."
  [tx uuid]
  (log/debug (format "Loading workload uuid=%s" uuid))
  (let [workloads (jdbc/query tx ["SELECT * FROM workload WHERE uuid = ?" uuid])]
    (when (empty? workloads)
      (throw (ex-info "No workload found matching uuid"
                      {:cause {:uuid uuid}
                       :type  ::workload-not-found})))
    (try-load-workload-impl tx (first workloads))))

(defn load-workload-for-id
  "Use transaction `tx` to load `workload` with `id`."
  [tx id]
  (log/debug (format "Loading workload id=%s" id))
  (let [workloads (jdbc/query tx ["SELECT * FROM workload WHERE id = ?" id])]
    (when (empty? workloads)
      (throw (ex-info "No workload found matching id"
                      {:cause {:id id}
                       :type  ::workload-not-found})))
    (try-load-workload-impl tx (first workloads))))

(defn load-workloads-with-project
  "Use transaction `tx` to load `workload`(s) with `project`."
  [tx project]
  (log/debug (format "Loading workloads with project=\"%s\"" project))
  (let [query-str "SELECT * FROM workload WHERE project = ? ORDER BY id ASC"]
    (mapv (partial load-workload-impl tx) (jdbc/query tx [query-str project]))))

(defn load-workloads
  "Use transaction `tx` to load all known `workloads`."
  [tx]
  (log/debug "Loading all workloads")
  (let [query-str "SELECT * FROM workload ORDER BY id ASC"]
    (mapv (partial load-workload-impl tx) (jdbc/query tx query-str))))

;; helper utility for point-free multi-method implementation registration.
(defmacro defoverload
  "Register a method IMPL to MULTIFN with DISPATCHVAL"
  [multifn dispatchval impl]
  `(defmethod ~multifn ~dispatchval [& xs#] (apply ~impl xs#)))

;; :default implementations
(defmethod create-workload!
  :default
  [_ {:keys [pipeline] :as request}]
  (throw
   (ex-info "Failed to create workload - no such pipeline"
            {:request  request
             :pipeline pipeline
             :type     ::invalid-pipeline})))

(defmethod start-workload!
  :default
  [_ {:keys [pipeline] :as workload}]
  (throw
   (ex-info "Failed to start workload - no such pipeline"
            {:workload  workload
             :pipeline  pipeline
             :type      ::invalid-pipeline})))

(defmethod stop-workload!
  :default
  [_ {:keys [pipeline] :as workload}]
  (throw
   (ex-info "Failed to stop workload - no such pipeline"
            {:workload workload
             :pipeline pipeline
             :type     ::invalid-pipeline})))

(defmethod execute-workload!
  :default
  [tx workload-request]
  (start-workload! tx (create-workload! tx workload-request)))

(defmethod update-workload!
  :default
  [_ {:keys [pipeline] :as workload}]
  (throw
   (ex-info "Failed to update workload - no such pipeline"
            {:workload workload
             :pipeline pipeline
             :type     ::invalid-pipeline})))

;; Visible for testing
(def retry-unimplemented-error-message
  "Retry unimplemented for pipeline")

(defmethod throw-if-invalid-retry-filters
  :default
  [workload filters]
  (throw
   (UserException. retry-unimplemented-error-message
                   {:workload (util/to-edn workload)
                    :filters  filters
                    :status   501})))

(defmethod retry
  :default
  [{:keys [pipeline] :as workload} _]
  (throw
   (ex-info "Failed to retry workflows - no such pipeline"
            {:workload workload
             :pipeline pipeline
             :type     ::invalid-pipeline})))

(defmethod to-edn
  :default
  [{:keys [pipeline] :as workload}]
  (throw
   (ex-info "Failed to coerce workload to EDN - no such pipeline"
            {:workload workload
             :pipeline pipeline
             :type     ::invalid-pipeline})))

(defmethod load-workload-impl
  :default
  [_ {:keys [pipeline] :as workload}]
  (throw
   (ex-info "Failed to load workload - no such pipeline"
            {:workload workload
             :pipeline pipeline
             :type     ::invalid-pipeline})))

;; Common workload operations
(defoverload util/to-edn :workload to-edn)

(defn saved-before?
  "Test if the `_workload` was saved before the `reference` version string.
   Version strings must be in the form \"major.minor.patch\"."
  [reference {:keys [version] :as _workload}]
  (letfn [(decode [v] (map util/parse-int (str/split v #"\.")))
          (validate [v] (when (not= 3 (count v))
                          (throw (ex-info "malformed version string"
                                          {:version (str/join "." v)})))
            v)
          (lt? [[x & xs] [y & ys]]
            (or (< x y) (and (== x y) (every? some? [xs ys]) (lt? xs ys))))]
    (util/on lt? (comp validate decode) version reference)))

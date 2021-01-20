(ns wfl.api.workloads
  (:require [clojure.string :as str]
            [wfl.jdbc :as jdbc]
            [wfl.service.postgres :as postgres]
            [wfl.util :as util]))

;; always derive from base :wfl/exception
(derive ::invalid-pipeline :wfl/exception)
(derive ::workload-not-found :wfl/exception)

;; creating and dispatching workloads to cromwell
(defmulti create-workload!
  "(transaction workload-request) -> workload"
  (fn [_ body] (:pipeline body)))

(defmulti start-workload!
  "(transaction workload) -> workload"
  (fn [_ body] (:pipeline body)))

(defmulti execute-workload!
  "(transaction workload) -> workload"
  (fn [_ body] (:pipeline body)))

(defmulti update-workload!
  "(transaction workload) -> workload"
  (fn [_ body] (:pipeline body)))

;; loading utilities
(defmulti load-workload-impl
  "Load the workload given a TRANSACTION and a partially loaded WORKLOAD.
  NOTE: do NOT call directly in product code - this is only meant to be called
  within this namespace."
  (fn [_ body] (:pipeline body)))

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
  (let [workloads (jdbc/query tx ["SELECT * FROM workload WHERE uuid = ?" uuid])]
    (when (empty? workloads)
      (throw (ex-info "No workload found matching uuid"
                      {:cause {:uuid uuid}
                       :type  ::workload-not-found})))
    (try-load-workload-impl tx (first workloads))))

(defn load-workload-for-id
  "Use transaction `tx` to load `workload` with `id`."
  [tx id]
  (let [workloads (jdbc/query tx ["SELECT * FROM workload WHERE id = ?" id])
        n (count workloads)]
    (when (not= 1 n)
      (throw (ex-info "Expected 1 workload matching id"
                      {:cause {:id id :count n}
                       :type  ::workload-not-found})))
    (try-load-workload-impl tx (first workloads))))

(defn load-workloads-with-project
  "Use transaction `tx` to load `workload`(s) with `project`."
  [tx project]
  (let [do-load   (partial load-workload-impl tx)]
    (mapv do-load
          (jdbc/query tx ["SELECT * FROM workload WHERE project = ?" project]))))

(defn load-workloads
  "Use transaction `tx` to load all known `workloads`."
  [tx]
  (let [do-load (partial load-workload-impl tx)]
    (mapv do-load (jdbc/query tx ["SELECT * FROM workload"]))))

;; helper utility for point-free multi-method implementation registration.
(defmacro defoverload
  "Register a method IMPL to MULTIFN with DISPATCHVAL"
  [multifn dispatchval impl]
  `(defmethod ~multifn ~dispatchval [& xs#] (apply ~impl xs#)))

;; :default implementations
(defmethod create-workload!
  :default
  [_ body]
  (throw
   (ex-info "Failed to create workload - no such pipeline"
            {:cause body
             :type  ::invalid-pipeline})))

(defmethod start-workload!
  :default
  [_ body]
  (throw
   (ex-info "Failed to start workload - no such pipeline"
            {:cause body
             :type  ::invalid-pipeline})))

(defmethod execute-workload!
  :default
  [tx workload-request]
  (start-workload! tx (create-workload! tx workload-request)))

(defmethod update-workload!
  :default
  [_ body]
  (throw
   (ex-info "Failed to update workload - no such pipeline"
            {:cause body
             :type  ::invalid-pipeline})))

(defn default-load-workload-impl
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

(defoverload load-workload-impl :default default-load-workload-impl)

;; Common workload operations
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

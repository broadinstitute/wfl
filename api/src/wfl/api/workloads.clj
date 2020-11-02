(ns wfl.api.workloads
  (:require [wfl.service.postgres :as postgres]
            [wfl.jdbc :as jdbc]
            [wfl.util :as util]
            [wfl.api.common :as common]))

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
    (->> workload
         (load-workload-impl tx)
         (common/load-common-into-workload tx))
    (catch Throwable cause
      (throw (ex-info "Error loading workload"
               {:workload workload} cause)))))

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
  (let [workloads (jdbc/query tx ["SELECT * FROM workload WHERE id = ?" id])]
    (when (empty? workloads)
      (throw (ex-info "No workload found matching id"
               {:cause {:id id}
                :type  ::workload-not-found})))
    (try-load-workload-impl tx (first workloads))))

(defn load-workloads
  "Use transaction TX to load all known `workloads`"
  [tx]
  (let [do-load (partial load-workload-impl tx)]
    (map do-load (jdbc/query tx ["SELECT * FROM workload"]))))

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
  (try
    (start-workload! tx (create-workload! tx workload-request))
    (catch Throwable cause
      (throw (ex-info "Error executing workload request"
               {:workload-request workload-request} cause)))))

(defmethod update-workload!
  :default
  [tx workload]
  (try
    (postgres/update-workload! tx workload)
    (load-workload-for-id tx (:id workload))
    (catch Throwable cause
      (throw (ex-info "Error updating workload"
               {:workload workload} cause)))))

(defn default-load-workload-impl
  [tx workload]
  (letfn [(unnilify [m] (into {} (filter second m)))
          (split-inputs [m]
            (let [keep [:id :finished :status :updated :uuid :workflow_options]]
              (assoc (select-keys m keep)
                :inputs (unnilify (apply dissoc m keep)))))
          (unpack-options [m]
            (update m :workflow_options #(when % (util/parse-json %))))]
    (try
      (->> (postgres/get-table tx (:items workload))
        (mapv (comp unnilify split-inputs unpack-options))
        (assoc workload :workflows)
        unnilify)
      (catch Throwable cause
        (throw (ex-info "Error loading workload"
                 {:workload workload} cause))))))

(defoverload load-workload-impl :default default-load-workload-impl)

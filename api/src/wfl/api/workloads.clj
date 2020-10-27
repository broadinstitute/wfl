(ns wfl.api.workloads
  (:require [wfl.service.postgres :as postgres]
            [wfl.jdbc :as jdbc]))

;; always derive from base :wfl/exception
(derive ::invalid-pipeline :wfl/exception)

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

(defn load-workload-for-uuid
  "Use transaction `tx` to load `workload` with `uuid`."
  [tx uuid]
  (when-first [workload (jdbc/query tx
                          ["SELECT * FROM workload WHERE uuid = ?" uuid])]
    (load-workload-impl tx workload)))

(defn load-workload-for-id
  "Use transaction `tx` to load `workload` with `id`."
  [tx id]
  (when-first [workload (jdbc/query tx
                          ["SELECT * FROM workload WHERE id = ?" id])]
    (load-workload-impl tx workload)))

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
       :type ::invalid-pipeline})))

(defmethod start-workload!
  :default
  [_ body]
  (throw
    (ex-info "Failed to start workload - no such pipeline"
      {:cause body
       :type ::invalid-pipeline})))

(defmethod execute-workload!
  :default
  [tx workload-request]
  (start-workload! tx (create-workload! tx workload-request)))

(defmethod update-workload!
  :default
  [tx workload]
  (postgres/update-workload! tx workload)
  (load-workload-for-id tx (:id workload)))

(defmethod load-workload-impl
  :default
  [tx workload]
  (letfn [(unnilify [m] (into {} (filter second m)))]
    (->>
      (postgres/get-table tx (:items workload))
      (map unnilify)
      (assoc workload :workflows)
      unnilify)))

(defn load-workflow-with-structure
  "Load WORKLOAD via TX like :default, then nesting values in workflows based on STRUCTURE."
  [tx workload structure]
  (letfn [(restructure-once [workflow new-key old-keys]
            (assoc (apply dissoc workflow old-keys) new-key (select-keys workflow old-keys)))
          (restructure-workflows [workflows]
            (map #(reduce-kv restructure-once % structure) workflows))]
    ;; We're calling clojure.lang.MultiFn's getMethod, not java.lang.Class's
    (update ((.getMethod load-workload-impl :default) tx workload) :workflows restructure-workflows)))

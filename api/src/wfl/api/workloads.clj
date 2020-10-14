(ns wfl.api.workloads
  (:require [wfl.service.postgres :as postgres]))

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
    (NoSuchMethodError.
      (format "No way to create workload for pipeline: %s" (:pipeline body)))))

(defmethod create-workload!
  :default
  [_ body]
  (throw
    (NoSuchMethodError.
      (format "No way to start workload for pipeline: %s" (:pipeline body)))))

(defmethod execute-workload!
  :default
  [tx workload-request]
  (start-workload! tx (create-workload! tx workload-request)))

(defmethod update-workload!
  :default
  [tx workload]
  (postgres/update-workload! tx workload)
  (postgres/load-workload-for-id tx (:id workload)))

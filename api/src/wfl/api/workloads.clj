(ns wfl.api.workloads
  (:require [clojure.string        :as str]
            [wfl.jdbc              :as jdbc]
            [wfl.reader            :refer :all]
            [wfl.service.postgres  :as postgres]
            [wfl.util              :as util]
            [clojure.tools.logging :as log]))

;; always derive from base :wfl/exception
(derive ::invalid-pipeline :wfl/exception)
(derive ::workload-not-found :wfl/exception)

;; creating and dispatching workloads to cromwell
(defmulti create-workload!
  "workload-request -> workload"
  (fn [body] (:pipeline body)))

(defmulti start-workload!
  "workload -> workload"
  (fn [body] (:pipeline body)))

(defmulti stop-workload!
  "workload -> workload"
  (fn [body] (:pipeline body)))

(defmulti execute-workload!
  "workload -> workload"
  (fn [body] (:pipeline body)))

(defmulti update-workload!
  "workload -> workload"
  (fn [body] (:pipeline body)))

;; loading utilities
(defmulti load-workload-impl
  "Load the workload given a TRANSACTION and a partially loaded WORKLOAD.
  NOTE: do NOT call directly in product code - this is only meant to be called
  within this namespace."
  (fn [body _] (:pipeline body)))

(defn ^:private try-load-workload-impl [workload]
  (catch-m
   (load-workload-impl workload)
   (fn [cause]
     (throw (ex-info (str "Error loading workload - " (.getMessage cause))
                     (assoc (ex-data cause) :workload workload)
                     cause)))))

(defn load-workload-for-uuid
  "Use transaction `tx` to load `workload` with `uuid`."
  [uuid]
  (log/debugf "Loading workload uuid=%s" uuid)
  (let-m [workloads (jdbc/query ["SELECT * FROM workload WHERE uuid = ?" uuid])]
    (when-m (empty? workloads)
      (throw (ex-info "No workload found matching uuid"
                      {:cause {:uuid uuid}
                       :type  ::workload-not-found})))
    (try-load-workload-impl (first workloads))))

(defn load-workload-for-id
  "Load `workload` with `id`."
  [id]
  (log/debugf "Loading workload id=%s" id)
  (let-m [workloads (jdbc/query ["SELECT * FROM workload WHERE id = ?" id])]
    (when-m (empty? workloads)
      (throw (ex-info "No workload found matching id"
                      {:cause {:id id}
                       :type  ::workload-not-found})))
    (try-load-workload-impl (first workloads))))

(defn load-workloads-with-project
  "Use transaction `tx` to load `workload`(s) with `project`."
  [project]
  (log/debugf "Loading workloads with project=\"%s\"" project)
  (->> (jdbc/query ["SELECT * FROM workload WHERE project = ?" project])
       (map-m load-workload-impl)))

(defn load-workloads
  "Use transaction `tx` to load all known `workloads`."
  []
  (log/debug "Loading all workloads")
  (map-m load-workload-impl (jdbc/query ["SELECT * FROM workload"])))

;; helper utility for point-free multi-method implementation registration.
(defmacro defoverload
  "Register a method IMPL to MULTIFN with DISPATCHVAL"
  [multifn dispatchval impl]
  `(defmethod ~multifn ~dispatchval [& xs#] (apply ~impl xs#)))

;; :default implementations
(defmethod create-workload!
  :default
  [body]
  (throw
   (ex-info "Failed to create workload - no such pipeline"
            {:cause body
             :type  ::invalid-pipeline})))

(defmethod start-workload!
  :default
  [body]
  (throw
   (ex-info "Failed to start workload - no such pipeline"
            {:cause body
             :type  ::invalid-pipeline})))

(defmethod stop-workload!
  :default
  [body]
  (throw
   (ex-info "Failed to stop workload - no such pipeline"
            {:cause body
             :type  ::invalid-pipeline})))

(defmethod execute-workload!
  :default
  [workload-request]
  (start-workload! (create-workload! workload-request)))

(defmethod update-workload!
  :default
  [body]
  (throw
   (ex-info "Failed to update workload - no such pipeline"
            {:cause body
             :type  ::invalid-pipeline})))

(defn default-load-workload-impl
  [workload]
  (letfn [(unnilify [m] (into {} (filter second m)))
          (split-inputs [m]
            (let [keep [:id :finished :status :updated :uuid :options]]
              (assoc (select-keys m keep) :inputs (apply dissoc m keep))))
          (load-options [m] (update m :options (fnil util/parse-json "null")))]
    (let-m [table     (postgres/get-table (:items workload))
            workflows (map-m (comp unnilify split-inputs load-options) table)]
      (return (unnilify (assoc workflow :workflows workflows))))))

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

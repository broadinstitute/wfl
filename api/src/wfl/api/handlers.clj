(ns wfl.api.handlers
  "Define handlers for API endpoints"
  (:require [clojure.tools.logging :as log]
            [clojure.tools.logging.readable :as logr]
            [ring.util.response :as response]
            [wfl.api.workloads :as workloads]
            [wfl.module.aou :as aou]
            [wfl.module.copyfile]
            [wfl.module.wgs]
            [wfl.module.xx]
            [wfl.jdbc :as jdbc]
            [wfl.service.cromwell :as cromwell]
            [wfl.service.postgres :as postgres]
            [wfl.wfl :as wfl]
            [wfl.service.gcs :as gcs]))

(defn fail
  "A failure response with BODY."
  [body]
  (log/warn "Endpoint returned 400 failure response:")
  (log/warn body)
  (-> body response/bad-request (response/content-type "application/json")))

(defmacro ^:private fail-on-error
  "Run BODY, printing any exception rises through it."
  [& body]
  `(try
     ~@body
     (catch Exception e#
       (fail e#))))

(defn succeed
  "A successful response with BODY."
  [body]
  (-> body response/response (response/content-type "application/json")))

(defn success
  "Respond successfully with BODY."
  [body]
  (constantly (succeed body)))

(defn status-counts
  "Get status counts for environment in REQUEST."
  [{:keys [parameters] :as _request}]
  (fail-on-error
    (let [environment (some :environment ((juxt :query :body) parameters))]
      (logr/infof "status-counts endpoint called: environment=%s" environment)
      (let [env (wfl/throw-or-environment-keyword! environment)]
        (succeed (cromwell/status-counts env {:includeSubworkflows false}))))))

(defn query-workflows
  "Get workflows for environment in REQUEST."
  [{:keys [parameters] :as _request}]
  (fail-on-error
    (let [{:keys [body environment query]} parameters]
      (logr/infof "query-workflows endpoint called: body=%s environment=%s query=%s" body environment query)
      (let [env   (wfl/throw-or-environment-keyword! environment)
            start (some :start [query body])
            end   (some :end [query body])
            query {:includeSubworkflows false :start start :end end}]
        (succeed {:results (cromwell/query env query)})))))

(defn append-to-aou-workload
  "Append new workflows to an existing started AoU workload describe in BODY of REQUEST."
  [request]
  (fail-on-error
    (let [{:keys [notifications uuid] :as body}
          (get-in request [:parameters :body])]
      (logr/infof "append-to-aou-workload endpoint called: body=%s" body)
      (jdbc/with-db-transaction [tx (postgres/wfl-db-config)]
        (->> (workloads/load-workload-for-uuid tx uuid)
          (aou/append-to-workload! tx notifications)
          succeed)))))

(defn post-create
  "Create the workload described in BODY of REQUEST."
  [{:keys [parameters] :as request}]
  (fail-on-error
    (let [{:keys [body]} parameters
          {:keys [email]} (gcs/userinfo request)]
      (logr/infof "post-create endpoint called: body=%s" body)
      (jdbc/with-db-transaction [tx (postgres/wfl-db-config)]
        (succeed
          (workloads/create-workload! tx
            (assoc body :creator email)))))))


(defn get-workload!
  "List all workloads or the workload with UUID in REQUEST."
  [request]
  (letfn [(go! [tx {:keys [uuid] :as workload}]
            (if (:finished workload)
              workload
              (do
                (logr/infof "updating workload %s" uuid)
                (workloads/update-workload! tx workload))))]
    (fail-on-error
      (jdbc/with-db-transaction [tx (postgres/wfl-db-config)]
        (succeed
          (mapv (partial go! tx)
            (if-let [uuid (-> request :parameters :query :uuid)]
              (if-let [workload (workloads/load-workload-for-uuid tx uuid)]
                [workload]
                (throw (ex-info "No such workload" {:uuid uuid})))
              (workloads/load-workloads tx))))))))

(defn post-start
  "Start the workloads with UUIDs in REQUEST."
  [request]
  (letfn [(go! [tx {:keys [uuid]}]
            (if-let [workload (workloads/load-workload-for-uuid tx uuid)]
              (if (:started workload)
                workload
                (do
                  (logr/infof "starting workload %s" uuid)
                  (workloads/start-workload! tx workload)))
              (throw (ex-info "No such workload" {:uuid uuid}))))]
    (fail-on-error
      (jdbc/with-db-transaction [tx (postgres/wfl-db-config)]
        (let [uuids (-> request :parameters :body distinct)]
          (logr/infof "post-start endpoint called: uuids=%s" uuids)
          (succeed (mapv #(go! tx %) uuids)))))))

(defn post-exec
  "Create and start workload described in BODY of REQUEST"
  [request]
  (fail-on-error
    (jdbc/with-db-transaction [tx (postgres/wfl-db-config)]
      (succeed
        (workloads/execute-workload! tx
          (assoc
            (get-in request [:parameters :body])
            :creator
            (:email (gcs/userinfo request))))))))

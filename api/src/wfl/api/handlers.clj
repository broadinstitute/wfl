* (ns wfl.api.handlers
    "Define handlers for API endpoints"
    (:require [clojure.string :as str]
              [clojure.tools.logging :as log]
              [clojure.tools.logging.readable :as logr]
              [ring.util.response :as response]
              [wfl.module.aou :as aou]
              [wfl.module.copyfile :as cp]
              [wfl.jdbc :as jdbc]
              [wfl.module.wgs :as wgs]
              [wfl.service.cromwell :as cromwell]
              [wfl.service.postgres :as postgres]
              [wfl.wfl :as wfl]
              [wfl.util :as util]
              [wfl.service.gcs :as gcs]))

(defn fail
  "A failure response with BODY."
  [body]
  (log/warn "Endpoint sent a 400 failure response:")
  (log/warn body)
  (-> body response/bad-request (response/content-type "application/json")))

(defmacro ^:private log-and-fail-on-error
  "Run BODY, printing any exception rises through it."
  [& body]
  `(try
     ~@body
     (catch Exception e#
       (log/error e#)
       (fail e#))))

(defn fail-with-response
  "A failure RESPONSE with BODY."
  [response body]
  (log/warn "Endpoint sent a failure response:")
  (log/warn body)
  (-> body response (response/content-type "application/json")))

(defn unprocessable-entity
  "Returns a 422 'Unprocessable Entity' response with BODY."
  [body]
  (let [response {:status  422
                  :headers {}
                  :body    body}]
    (log/warn "Endpoint sent a 422 failure response:")
    (log/warn body)
    response))

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
  (log-and-fail-on-error
    (let [environment (some :environment ((juxt :query :body) parameters))]
      (logr/infof "status-counts endpoint called: environment=%s" environment)
      (let [env (wfl/throw-or-environment-keyword! environment)]
        (succeed (cromwell/status-counts env {:includeSubworkflows false}))))))

(defn query-workflows
  "Get workflows for environment in REQUEST."
  [{:keys [parameters] :as _request}]
  (log-and-fail-on-error
    (let [{:keys [body environment query]} parameters]
      (logr/infof "query-workflows endpoint called: body=%s environment=%s query=%s" body environment query)
      (let [env   (wfl/throw-or-environment-keyword! environment)
            start (some :start [query body])
            end   (some :end [query body])
            query {:includeSubworkflows false :start start :end end}]
        (succeed {:results (cromwell/query env query)})))))

(defn append-to-aou-workload
  "Append new workflows to an existing started AoU workload describe in BODY of _REQUEST."
  [{:keys [parameters] :as _request}]
  (log-and-fail-on-error
    (let [{:keys [body]} parameters]
      (logr/infof "append-to-aou-workload endpoint called: body=%s" body)
      (jdbc/with-db-transaction [tx (postgres/wfl-db-config)]
        (succeed (aou/append-to-workload! tx body))))))

(defn on-unknown-pipeline
  "Fail this request returning BODY as result."
  [_ body]
  (fail {:add-workload-failed body}))

(defmacro defoverload
  "Register a method IMPL to MULTIFUN using DISPATCH_VAL"
  [multifn dispatch-val impl]
  `(defmethod ~multifn ~dispatch-val [& xs#] (apply ~impl xs#)))

(defmulti add-workload! (fn [_ body] (:pipeline body)))
(defoverload add-workload! :default on-unknown-pipeline)
(defoverload add-workload! aou/pipeline aou/add-workload!)
(defoverload add-workload! cp/pipeline cp/add-workload!)
(defoverload add-workload! wgs/pipeline wgs/add-workload!)

(defmulti start-workload! (fn [_ body] (:pipeline body)))
(defoverload start-workload! :default on-unknown-pipeline)
(defoverload start-workload! aou/pipeline aou/start-workload!)
(defoverload start-workload! cp/pipeline cp/start-workload!)
(defoverload start-workload! wgs/pipeline wgs/start-workload!)

(defn post-create
  "Create the workload described in BODY of REQUEST."
  [{:keys [parameters] :as request}]
  (log-and-fail-on-error
    (let [{:keys [body]} parameters
          {:keys [email]} (gcs/userinfo request)]
      (logr/infof "post-create endpoint called: body=%s" body)
      (jdbc/with-db-transaction [tx (postgres/wfl-db-config)]
        (->>
          (assoc body :creator email)
          (add-workload! tx)
          :uuid
          (postgres/load-workload-for-uuid tx)
          succeed)))))

(defn get-workload!
  "List all workloads or the workload with UUID in REQUEST."
  [request]
  (letfn [(go! [tx {:keys [uuid id] :as workload}]
            (if-not (:finished workload)
              (do
                (logr/infof "updating workload %s" uuid)
                (postgres/update-workload! tx workload)
                (postgres/load-workload-for-id tx id))
              workload))]
    (log-and-fail-on-error
      (jdbc/with-db-transaction [tx (postgres/wfl-db-config)]
        (succeed
          (mapv (partial go! tx)
            (if-let [uuid (-> request :parameters :query :uuid)]
              (if-let [workload (postgres/load-workload-for-uuid tx uuid)]
                [workload]
                (throw (ex-info "No such workload" {:uuid uuid})))
              (postgres/load-workloads tx))))))))

(defn post-start
  "Start the workloads with UUIDs in REQUEST."
  [request]
  (letfn [(go! [tx {:keys [uuid]}]
            (if-let [workload (postgres/load-workload-for-uuid tx uuid)]
              (do
                (start-workload! tx workload)
                (postgres/load-workload-for-id tx (:id workload)))
              (throw
                (ex-info "No such workload" {:uuid uuid}))))]
    (log-and-fail-on-error
      (jdbc/with-db-transaction [tx (postgres/wfl-db-config)]
        (let [uuids (-> request :parameters :body distinct)]
          (logr/infof "post-start endpoint called: uuids=%s" uuids)
          (succeed (mapv #(go! tx %) uuids)))))))

(defn post-exec
  "Create and start workload described in BODY of REQUEST"
  [request]
  (letfn [(create! [tx workload-request]
            (->>
              (add-workload! tx workload-request)
              :uuid
              (postgres/load-workload-for-uuid tx)))
          (start! [tx workload]
            (start-workload! tx workload)
            (postgres/load-workload-for-id tx (:id workload)))
          (exec! [tx workload-request]
            (start! tx (create! tx workload-request)))]
    (log-and-fail-on-error
      (jdbc/with-db-transaction [tx (postgres/wfl-db-config)]
        (succeed
          (exec! tx
            (assoc
              (get-in request [:parameters :body])
              :creator
              (:email (gcs/userinfo request)))))))))

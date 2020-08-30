* (ns zero.api.handlers
    "Define handlers for API endpoints"
    (:require [clojure.string :as str]
              [clojure.tools.logging :as log]
              [clojure.tools.logging.readable :as logr]
              [ring.util.response :as response]
              [zero.module.aou :as aou]
              [zero.module.copyfile :as cp]
              [zero.jdbc :as jdbc]
              [zero.module.wgs :as wgs]
              [zero.service.cromwell :as cromwell]
              [zero.service.postgres :as postgres]
              [zero.zero :as zero]))

(defmacro ^:private print-handler-error
  "Run BODY, printing any exception rises through it."
  [& body]
  `(try
     ~@body
     (catch Exception e#
       (log/error e# "Exception rose uncaught through handler")
       (throw e#))))

(defn fail
  "A failure response with BODY."
  [body]
  (log/warn "Endpoint sent a 400 failure response:")
  (log/warn body)
  (-> body response/bad-request (response/content-type "application/json")))

(defn fail-with-response
  "A failure RESPONSE with BODY."
  [response body]
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
  (zero.api.handlers/print-handler-error
   (let [environment (some :environment ((juxt :query :body) parameters))]
     (logr/infof "status-counts endpoint called: environment=%s" environment)
     (let [env (zero/throw-or-environment-keyword! environment)]
       (succeed (cromwell/status-counts env {:includeSubworkflows false}))))))

(defn query-workflows
  "Get workflows for environment in REQUEST."
  [{:keys [parameters] :as _request}]
  (zero.api.handlers/print-handler-error
   (let [{:keys [body environment query]} parameters]
     (logr/infof "query-workflows endpoint called: body=%s environment=%s query=%s" body environment query)
     (let [env   (zero/throw-or-environment-keyword! environment)
           start (some :start [query body])
           end   (some :end [query body])
           query {:includeSubworkflows false :start start :end end}]
       (succeed {:results (cromwell/query env query)})))))

(defn append-to-aou-workload
  "Append new workflows to an existing started AoU workload describe in BODY of _REQUEST."
  [{:keys [parameters] :as _request}]
  (zero.api.handlers/print-handler-error
   (let [{:keys [body]} parameters]
     (logr/infof "append-to-aou-workload endpoint called: body=%s" body)
     (try
       (jdbc/with-db-transaction [tx (postgres/zero-db-config)]
         (->> body
              (aou/append-to-workload! tx)
              succeed))
       (catch Exception e
         (log/warn e "Exception in appending to aou workload")
         (fail-with-response unprocessable-entity {:message (.getMessage e)}))))))

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
  "Create the workload described in BODY of _REQUEST."
  [{:keys [parameters] :as _request}]
  (zero.api.handlers/print-handler-error
   (letfn [(unnilify [m] (into {} (filter second m)))]
     (let [{:keys [body]} parameters]
       (logr/infof "post-create endpoint called: body=%s" body)
       (jdbc/with-db-transaction [tx (postgres/zero-db-config)]
         (->> body
              (add-workload! tx)
              :uuid
              (conj ["SELECT * FROM workload WHERE uuid = ?"])
              (jdbc/query tx)
              first unnilify succeed))))))

(defn get-workload
  "List all workloads or the workload with UUID in REQUEST."
  [request]
  (zero.api.handlers/print-handler-error
   (jdbc/with-db-transaction [tx (postgres/zero-db-config)]
     (->> (if-let [uuid (get-in request [:parameters :query :uuid])]
            (do
              (logr/infof "get-workload endpoint called: uuid=%s" uuid)
              [{:uuid uuid}])
            (do
              (logr/info "get-workload endpoint called with no uuid, querying all")
              (jdbc/query tx ["SELECT uuid FROM workload"])))
          (mapv (partial postgres/get-workload-for-uuid tx))
          succeed))))

(defn post-start
  "Start the workloads with UUIDs in REQUEST."
  [request]
  (zero.api.handlers/print-handler-error
   (let [uuids (-> request :parameters :body distinct)]
     (logr/infof "post-start endpoint called: uuids=%s" uuids)
     (letfn [(q [[left right]] (fn [it] (str left it right)))]
       (let [db-config (postgres/zero-db-config)
             db-conn (jdbc/get-connection db-config)
             query (->> (repeat (count uuids) "?")
                        (str/join ",") ((q "()"))
                        (format "SELECT * FROM workload WHERE uuid in %s"))
             ps (jdbc/prepare-statement db-conn query)]
         (doseq [[i uuid] (map-indexed vector uuids)] (.setString ps (+ i 1) (:uuid uuid)))
         (jdbc/with-db-transaction [tx db-config]
           (->> ps
                (jdbc/query tx)
                (run! (partial start-workload! tx)))
           (->> uuids
                (mapv (partial postgres/get-workload-for-uuid tx))
                succeed)))))))

(def post-exec
  "Create and start workload described in BODY of REQUEST"
  (let [pack   (fn [response] {:parameters {:body [(:body response)]}})
        unpack (comp first :body)]
    (comp succeed unpack post-start pack post-create)))

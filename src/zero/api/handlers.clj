(ns zero.api.handlers
  "Define handlers for API endpoints"
  (:require [clojure.java.jdbc :as jdbc]
            [clojure.string :as str]
            [ring.util.response :as response]
            [zero.module.aou :as aou]
            [zero.module.copyfile :as cp]
            [zero.module.wgs :as wgs]
            [zero.module.wl :as wl]
            [zero.service.cromwell :as cromwell]
            [zero.service.postgres :as postgres]
            [zero.zero :as zero]))

(defn fail
  "A failure response with BODY."
  [body]
  (-> body response/bad-request (response/content-type "application/json")))

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
  (let [environment (some :environment ((juxt :query :body) parameters))
        env         (zero/throw-or-environment-keyword! environment)]
    (succeed (cromwell/status-counts env {:includeSubworkflows false}))))

(defn query-workflows
  "Get workflows for environment in REQUEST."
  [{:keys [parameters] :as _request}]
  (let [{:keys [body environment query]} parameters
        env   (zero/throw-or-environment-keyword! environment)
        start (some :start [query body])
        end   (some :end [query body])
        query {:includeSubworkflows false :start start :end end}]
    (succeed {:results (cromwell/query env query)})))

(defn submit-wgs
  "Submit the WGS workload described in REQUEST."
  [{:keys [parameters] :as _request}]
  (let [{:keys [environment input_path max output_path]} (:body parameters)
        env     (zero/throw-or-environment-keyword! environment)
        results (wgs/submit-some-workflows env max input_path output_path)]
    (succeed {:results results})))

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
(defoverload add-workload! wl/pipeline wl/add-workload!)

(defmulti start-workload! (fn [_ body] (:pipeline body)))
(defoverload start-workload! :default on-unknown-pipeline)
(defoverload start-workload! aou/pipeline aou/start-workload!)
(defoverload start-workload! cp/pipeline cp/start-workload!)
(defoverload start-workload! wl/pipeline wl/start-workload!)

(defn post-create
  "Create the workload described in BODY of _REQUEST."
  [{:keys [parameters] :as _request}]
  (letfn [(unnilify [m] (into {} (filter second m)))]
    (let [{:keys [body]} parameters]
      (jdbc/with-db-transaction [tx (postgres/zero-db-config)]
        (->> body
             (add-workload! tx)
             :uuid
             (conj ["SELECT * FROM workload WHERE uuid = ?"])
             (jdbc/query tx)
             first unnilify succeed)))))

(defn get-workload
  "List all workloads or the workload with UUID in REQUEST."
  [request]
  (jdbc/with-db-transaction [tx (postgres/zero-db-config)]
    (->> (if-let [uuid (get-in request [:parameters :query :uuid])]
           [{:uuid uuid}]
           (jdbc/query tx ["SELECT uuid FROM workload"]))
         (mapv (partial postgres/get-workload-for-uuid tx))
         succeed)))

(defn post-start
  "Start the workloads with UUIDs in REQUEST."
  [request]
  (let [uuids (-> request :parameters :body distinct)]
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
               succeed))))))

(def post-exec
  "Create and start workload described in BODY of REQUEST"
  (let [pack   (fn [response] {:parameters {:body [(:body response)]}})
        unpack (comp first :body)]
    (comp succeed unpack post-start pack post-create)))

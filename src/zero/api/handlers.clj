(ns zero.api.handlers
  "Define handlers for API endpoints"
  (:require [clojure.java.jdbc     :as jdbc]
            [clojure.string        :as str]
            [ring.util.response    :as response]
            [zero.module.aou       :as aou]
            [zero.module.wgs       :as wgs]
            [zero.module.wl        :as wl]
            [zero.service.cromwell :as cromwell]
            [zero.service.postgres :as postgres]
            [zero.util             :as util]
            [zero.zero             :as zero]))

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
        end   (some :end   [query body])
        query {:includeSubworkflows false :start start :end end}]
    (succeed {:results (cromwell/query env query)})))

(defn list-workloads
  "List workloads for environment in REQUEST."
  [{:keys [parameters] :as _request}]
  (succeed {:results (-> parameters
                         :query :environment
                         zero/throw-or-environment-keyword!
                         (postgres/query :zero-db "SELECT * FROM workload"))}))

(defn submit-wgs
  "Submit the WGS workload described in REQUEST."
  [{:keys [parameters] :as _request}]
  (let [{:keys [environment input_path max output_path]} (:body parameters)
        env     (zero/throw-or-environment-keyword! environment)
        results (wgs/submit-some-workflows env max input_path output_path)]
    (succeed {:results results})))

(defn add-fail
  "Fail this request returning BODY as result."
  [body]
  (fail {:add-workload-failed body}))

(defn post-create
  "Create the workload described in BODY of REQUEST."
  [{:keys [parameters] :as request}]
  (letfn [(unnilify [m] (into {} (filter second m)))]
    (let [environment (keyword (util/getenv "ENVIRONMENT" "debug"))
          {:keys [body]} parameters
          add {"AllOfUsArrays"                   aou/add-workload!
               "ExternalWholeGenomeReprocessing" wl/add-workload!}
          add! (add (:pipeline body) add-fail)]
      (jdbc/with-db-transaction [tx (postgres/zero-db-config environment)]
        (->> body
             (add! tx)
             :uuid
             (conj ["SELECT * FROM workload WHERE uuid = ?"])
             (jdbc/query tx)
             first unnilify succeed)))))

(defn get-workload
  "List all workloads or the workload with UUID in REQUEST."
  [request]
  (let [environment (keyword (util/getenv "ENVIRONMENT" "debug"))]
    (jdbc/with-db-transaction [tx (postgres/zero-db-config environment)]
      (->> (if-let [uuid (get-in request [:parameters :query :uuid])]
             [{:uuid uuid}]
             (jdbc/query tx ["SELECT uuid FROM workload"]))
           (mapv (partial postgres/get-workload-for-uuid tx))
           succeed))))

(defn post-start
  "Start the workloads with UUIDs in REQUEST."
  [request]
  (let [start {"AllOfUsArrays"                   aou/start-workload!
               "ExternalWholeGenomeReprocessing"  wl/start-workload!}
        env   (keyword (util/getenv "ENVIRONMENT" "debug"))
        uuids (-> request :parameters :body distinct)]
    (letfn [(q [[left right]] (fn [it] (str left it right)))
            (start! [tx {:keys [pipeline] :as workload}]
              ((start pipeline) tx workload))]
      (jdbc/with-db-transaction [tx (postgres/zero-db-config env)]
        (->> uuids
             (map :uuid)
             (map (q "''")) (str/join ",") ((q "()"))
             (format "SELECT * FROM workload WHERE uuid in %s")
             (jdbc/query tx)
             (run! (partial start! tx)))
        (->> uuids
             (mapv (partial postgres/get-workload-for-uuid tx))
             succeed)))))

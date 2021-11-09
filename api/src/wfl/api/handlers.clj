(ns wfl.api.handlers
  "Define handlers for API endpoints. Require wfl.module namespaces here."
  (:require [clojure.set                    :refer [rename-keys]]
            [wfl.wfl                        :as wfl]
            [ring.util.http-response        :as response]
            [wfl.api.workloads              :as workloads]
            [wfl.configuration              :as config]
            [wfl.jdbc                       :as jdbc]
            [wfl.log                        :as log]
            [wfl.module.aou                 :as aou]
            [wfl.module.copyfile]
            [wfl.module.covid]
            [wfl.module.sg]
            [wfl.module.wgs]
            [wfl.module.xx]
            [wfl.service.google.storage     :as gcs]
            [wfl.service.postgres           :as postgres]
            [wfl.util                       :as util])
  (:import  [wfl.util UserException]))

(defn succeed
  "A successful response with BODY."
  [body]
  (-> body response/ok (response/content-type "application/json")))

(defn success
  "Respond successfully with BODY."
  [body]
  (constantly (succeed body)))

(defn oauth-redirect
  "Return the html page for the oauth redirect"
  [_]
  (-> (wfl/get-wfl-resource "oauth2-redirect.html")
      response/ok
      (response/content-type "text/html")))

(defn get-logging-level
  "Gets the current logging level of the API"
  [request]
  (log/info (select-keys request [:request-method :uri :body-params]))
  (jdbc/with-db-transaction [tx (postgres/wfl-db-config)]
    (letfn [(to-result [s] {:level s})]
      (-> (config/get-config tx "LOGGING_LEVEL")
          to-result
          succeed))))

(defn update-logging-level
  "Updates the current logging level of the API."
  [request]
  (log/info (select-keys request [:request-method :uri :parameters]))
  (let [{:keys [level]} (get-in request [:parameters :query])]
    (letfn [(to-result [s] {:level s})]
      (jdbc/with-db-transaction [tx (postgres/wfl-db-config)]
        (-> (config/upsert-config tx "LOGGING_LEVEL" level)
            first
            (get :value)
            to-result
            succeed)))))

(defn append-to-aou-workload
  "Append workflows described in BODY of REQUEST to a started AoU workload."
  [request]
  (log/info (select-keys request [:request-method :uri :body-params]))
  (let [{:keys [notifications uuid]} (get-in request [:parameters :body])]
    (jdbc/with-db-transaction [tx (postgres/wfl-db-config)]
      (->> (workloads/load-workload-for-uuid tx uuid)
           (aou/append-to-workload! tx notifications)
           succeed))))

(defn post-create
  "Create the workload described in REQUEST."
  [request]
  (log/info (select-keys request [:request-method :uri :body-params]))
  (let [workload-request (rename-keys (:body-params request)
                                      {:cromwell :executor})
        {:keys [email]}  (gcs/userinfo request)]
    (jdbc/with-db-transaction [tx (postgres/wfl-db-config)]
      (->> (assoc workload-request :creator email)
           (workloads/create-workload! tx)
           util/to-edn
           succeed))))

(defn get-workload
  "List all workloads or the workload(s) with UUID or PROJECT in REQUEST."
  [request]
  (log/info (select-keys request [:request-method :uri :parameters]))
  (let [{:keys [uuid project]} (get-in request [:parameters :query])]
    (succeed
     (map util/to-edn
          (jdbc/with-db-transaction [tx (postgres/wfl-db-config)]
            (cond uuid    [(workloads/load-workload-for-uuid tx uuid)]
                  project (workloads/load-workloads-with-project tx project)
                  :else   (workloads/load-workloads tx)))))))

(defn get-workflows
  "Return the workflows managed by the workload."
  [request]
  (log/info (select-keys request [:request-method :uri :parameters]))
  (let [uuid    (get-in request [:path-params :uuid])
        filters (-> request
                    (get-in [:parameters :query])
                    (select-keys [:submission :status]))]
    (jdbc/with-db-transaction [tx (postgres/wfl-db-config)]
      (->> (let [workload (workloads/load-workload-for-uuid tx uuid)]
             (if (empty? filters)
               (workloads/workflows tx workload)
               (workloads/workflows-by-filters tx workload filters)))
           (mapv util/to-edn)
           succeed))))

;; Visible for testing
(def retry-no-workflows-error-message
  "No workflows to retry for workload and requested workflow filters.")

(defn post-retry
  "Retry the workflows identified in `request`."
  [{:keys [body-params path-params] :as request}]
  (log/info (select-keys request [:request-method :uri :parameters]))
  (let [uuid     (:uuid path-params)
        filters  (select-keys body-params [:submission :status])
        workload (jdbc/with-db-transaction [tx (postgres/wfl-db-config)]
                   (workloads/load-workload-for-uuid tx uuid))]
    (workloads/throw-if-invalid-retry-filters workload filters)
    (let [workflows (jdbc/with-db-transaction [tx (postgres/wfl-db-config)]
                      (workloads/workflows-by-filters tx workload filters))]
      (when (empty? workflows)
        (throw (UserException. retry-no-workflows-error-message
                               {:workload uuid
                                :filters  filters
                                :status   400})))
      (->> (workloads/retry workload workflows)
           util/to-edn
           succeed))))

(defn post-start
  "Start the workload with UUID in REQUEST."
  [request]
  (log/info (select-keys request [:request-method :uri :parameters]))
  (let [{uuid :uuid} (:body-params request)]
    (jdbc/with-db-transaction [tx (postgres/wfl-db-config)]
      (let [{:keys [started] :as workload}
            (workloads/load-workload-for-uuid tx uuid)]
        (-> (if-not started (workloads/start-workload! tx workload) workload)
            util/to-edn
            succeed)))))

(defn post-stop
  "Stop managing workflows for the workload specified by 'request'."
  [request]
  (log/info (select-keys request [:request-method :uri :parameters]))
  (let [{uuid :uuid} (:body-params request)]
    (jdbc/with-db-transaction [tx (postgres/wfl-db-config)]
      (->> (workloads/load-workload-for-uuid tx uuid)
           (workloads/stop-workload! tx)
           util/to-edn
           succeed))))

(defn post-exec
  "Create and start workload described in BODY of REQUEST"
  [request]
  (log/info (select-keys request [:request-method :uri :parameters]))
  (let [workload-request (rename-keys (:body-params request)
                                      {:cromwell :executor})]
    (jdbc/with-db-transaction [tx (postgres/wfl-db-config)]
      (->> (gcs/userinfo request)
           :email
           (assoc workload-request :creator)
           (workloads/execute-workload! tx)
           util/to-edn
           succeed))))

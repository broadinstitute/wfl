(ns wfl.api.handlers
  "Define handlers for API endpoints"
  (:require [clojure.tools.logging :as log]
            [clojure.tools.logging.readable :as logr]
            [ring.util.http-response :as response]
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

(defn succeed
  "A successful response with BODY."
  [body]
  (-> body response/ok (response/content-type "application/json")))

(defn success
  "Respond successfully with BODY."
  [body]
  (constantly (succeed body)))

(defn status-counts
  "Get status counts for environment in REQUEST."
  [{:keys [parameters] :as _request}]
  (let [environment (some :environment ((juxt :query :body) parameters))]
    (logr/infof "status-counts endpoint called: environment=%s" environment)
    (let [env (wfl/throw-or-environment-keyword! environment)]
      (succeed (cromwell/status-counts env {:includeSubworkflows false})))))

(defn query-workflows
  "Get workflows for environment in REQUEST."
  [{:keys [parameters] :as _request}]
  (let [{:keys [body environment query]} parameters]
    (logr/infof "query-workflows endpoint called: body=%s environment=%s query=%s" body environment query)
    (let [env   (wfl/throw-or-environment-keyword! environment)
          start (some :start [query body])
          end   (some :end [query body])
          query {:includeSubworkflows false :start start :end end}]
      (succeed {:results (cromwell/query env query)}))))

(defn append-to-aou-workload
  "Append new workflows to an existing started AoU workload describe in BODY of REQUEST."
  [request]
  (let [{:keys [notifications uuid]} (get-in request [:parameters :body])]
    (logr/infof "appending %s samples to workload %s" (count notifications) uuid)
    (jdbc/with-db-transaction [tx (postgres/wfl-db-config)]
      (->> (workloads/load-workload-for-uuid tx uuid)
        (aou/append-to-workload! tx notifications)
        succeed))))

(defn post-create
  "Create the workload described in BODY of REQUEST."
  [{:keys [parameters] :as request}]
  (let [{:keys [body]} parameters
        {:keys [email]} (gcs/userinfo request)]
    (logr/infof "post-create endpoint called: body=%s" body)
    (jdbc/with-db-transaction [tx (postgres/wfl-db-config)]
      (succeed
        (workloads/create-workload! tx
          (assoc body :creator email))))))

(defn get-workload!
  "List all workloads or the workload with UUID in REQUEST."
  [request]
  (letfn [(go! [tx {:keys [uuid] :as workload}]
            (if (:finished workload)
              workload
              (do
                (logr/infof "updating workload %s" uuid)
                (workloads/update-workload! tx workload))))]
    (jdbc/with-db-transaction [tx (postgres/wfl-db-config)]
      (succeed
        (mapv (partial go! tx)
          (if-let [uuid (-> request :parameters :query :uuid)]
            [(workloads/load-workload-for-uuid tx uuid)]
            (if-let [project (-> request :parameters :query :project)]
              [(workloads/load-workload-for-project tx project)]
              (workloads/load-workloads tx))))))))

(defn post-start
  "Start the workload with UUID in REQUEST."
  [request]
  (jdbc/with-db-transaction [tx (postgres/wfl-db-config)]
    (let [{uuid :uuid} (:body-params request)]
      (logr/infof "post-start endpoint called: uuid=%s" uuid)
      (let [workload (workloads/load-workload-for-uuid tx uuid)]
        (->>
          (if (:started workload)
            workload
            (do
              (logr/infof "starting workload %s" uuid)
              (workloads/start-workload! tx workload)))
          succeed)))))

(defn post-exec
  "Create and start workload described in BODY of REQUEST"
  [request]
  (jdbc/with-db-transaction [tx (postgres/wfl-db-config)]
    (let [workload-request (:body-params request)]
      (logr/info "executing workload-request: " workload-request)
      (->>
        (gcs/userinfo request)
        :email
        (assoc workload-request :creator)
        (workloads/execute-workload! tx)
        succeed))))

(comment

  )
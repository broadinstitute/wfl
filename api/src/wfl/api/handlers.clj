(ns wfl.api.handlers
  "Define handlers for API endpoints. Note that pipeline modules MUST be required here."
  (:require [clojure.set                    :refer [rename-keys]]
            [clojure.tools.logging          :as log]
            [clojure.tools.logging.readable :as logr]
            [ring.util.http-response        :as response]
            [wfl.api.workloads              :as workloads]
            [wfl.debug]
            [wfl.jdbc                       :as jdbc]
            [wfl.module.aou                 :as aou]
            [wfl.module.arrays]
            [wfl.module.copyfile]
            [wfl.module.covid]
            [wfl.module.sg]
            [wfl.module.wgs]
            [wfl.module.xx]
            [wfl.service.google.storage     :as gcs]
            [wfl.service.postgres           :as postgres]))

(defn succeed
  "A successful response with BODY."
  [body]
  (-> body response/ok (response/content-type "application/json")))

(defn success
  "Respond successfully with BODY."
  [body]
  (constantly (succeed body)))

(defn ^:private prune
  [coll]
  (->> (apply dissoc coll [:id :items])
       (filter second)
       (into {})))

(defn strip-workflow-internals
  [workflow]
  (prune workflow))

(defn append-to-aou-workload
  "Append workflows described in BODY of REQUEST to a started AoU workload."
  [request]
  (let [{:keys [notifications uuid]} (get-in request [:parameters :body])]
    (logr/infof "appending %s samples to workload %s" (count notifications) uuid)
    (jdbc/with-db-transaction [tx (postgres/wfl-db-config)]
      (->> (workloads/load-workload-for-uuid tx uuid)
           (aou/append-to-workload! tx notifications)
           succeed))))

(defn post-create
  "Create the workload described in REQUEST."
  [request]
  (let [workload-request (-> (:body-params request)
                             (rename-keys {:cromwell :executor}))
        {:keys [email]}  (gcs/userinfo request)]
    (wfl.debug/trace workload-request)
    (logr/info "POST /api/v1/create with request: " workload-request)
    (-> (jdbc/with-db-transaction [tx (postgres/wfl-db-config)]
          (->> email
               (assoc workload-request :creator)
               (workloads/create-workload! tx)))
        strip-internals
        wfl.debug/trace
        succeed)))

(defn get-workload
  "List all workloads or the workload(s) with UUID or PROJECT in REQUEST."
  [request]
  (let [{:keys [uuid project] :as query} (get-in request [:parameters :query])]
    (logr/info "GET /api/v1/workload with query: " query)
    (succeed
     (map strip-internals
          (jdbc/with-db-transaction [tx (postgres/wfl-db-config)]
            (cond uuid    [(workloads/load-workload-for-uuid tx uuid)]
                  project (workloads/load-workloads-with-project tx project)
                  :else   (workloads/load-workloads tx)))))))

(defn get-workflows
  "Return the workflows managed by the workload."
  [request]
  (let [uuid (get-in request [:path-params :uuid])]
    (log/infof "GET /api/v1/workload/%s/workflows" uuid)
    (jdbc/with-db-transaction [tx (postgres/wfl-db-config)]
      (->> (workloads/load-workload-for-uuid tx uuid)
           (workloads/workflows tx)
           (mapv strip-workflow-internals)
           succeed))))

(defn post-start
  "Start the workload with UUID in REQUEST."
  [request]
  (let [{uuid :uuid} (:body-params request)]
    (logr/infof "POST /api/v1/start with uuid: " uuid)
    (-> (jdbc/with-db-transaction [tx (postgres/wfl-db-config)]
          (let [{:keys [started] :as workload}
                (workloads/load-workload-for-uuid tx uuid)]
            (if started workload (workloads/start-workload! tx workload))))
        strip-internals
        succeed)))

(defn post-stop
  "Stop managing workflows for the workload specified by 'request'."
  [request]
  (let [{uuid :uuid} (:body-params request)]
    (logr/infof "POST /api/v1/stop with uuid: %s" uuid)
    (-> (jdbc/with-db-transaction [tx (postgres/wfl-db-config)]
          (->> (workloads/load-workload-for-uuid tx uuid)
               (workloads/stop-workload! tx)))
        strip-internals
        succeed)))

(defn post-exec
  "Create and start workload described in BODY of REQUEST"
  [request]
  (let [workload-request (-> (:body-params request)
                             (rename-keys {:cromwell :executor}))]
    (logr/info "POST /api/v1/exec with request: " workload-request)
    (-> (jdbc/with-db-transaction [tx (postgres/wfl-db-config)]
          (->> (gcs/userinfo request)
               :email
               (assoc workload-request :creator)
               (workloads/execute-workload! tx)))
        strip-internals
        succeed)))

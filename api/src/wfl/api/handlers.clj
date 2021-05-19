(ns wfl.api.handlers
  "Define handlers for API endpoints. Note that pipeline modules MUST be required here."
  (:require [clojure.set                    :refer [rename-keys]]
            [clojure.tools.logging          :as log]
            [clojure.tools.logging.readable :as logr]
            [ring.util.http-response        :as response]
            [wfl.api.workloads              :as workloads]
            [wfl.module.aou                 :as aou]
            [wfl.module.arrays]
            [wfl.module.copyfile]
            [wfl.module.wgs]
            [wfl.module.xx]
            [wfl.module.sg]
            [wfl.jdbc                       :as jdbc]
            [wfl.service.google.storage     :as gcs]
            [wfl.service.postgres           :as postgres]
            [wfl.util                       :as util]))

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

(defn strip-internals
  "Strip internal properties from the `workload` and its `workflows`."
  [workload]
  (prune workload))

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
  "Create the workload described in REQUEST."
  [request]
  (let [workload-request (-> (:body-params request)
                             (rename-keys {:cromwell :executor}))
        {:keys [email]}  (gcs/userinfo request)]
    (logr/info "POST /api/v1/create with request: " workload-request)
    (jdbc/with-db-transaction [tx (postgres/wfl-db-config)]
      (->> (assoc workload-request :creator email)
           (workloads/create-workload! tx)
           util/to-edn
           succeed))))

(defn get-workload
  "List all workloads or the workload(s) with UUID or PROJECT in REQUEST."
  [request]
  (let [{:keys [uuid project] :as query} (get-in request [:parameters :query])]
    (logr/info "GET /api/v1/workload with query: " query)
    (succeed
     (map util/to-edn
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
           (mapv util/to-edn)
           succeed))))

(defn post-start
  "Start the workload with UUID in REQUEST."
  [request]
  (let [{uuid :uuid} (:body-params request)]
    (logr/infof "POST /api/v1/start with uuid: " uuid)
    (jdbc/with-db-transaction [tx (postgres/wfl-db-config)]
      (let [{:keys [started] :as workload}
            (workloads/load-workload-for-uuid tx uuid)]
        (-> (if-not started (workloads/start-workload! tx workload) workload)
            util/to-edn
            succeed)))))

(defn post-stop
  "Stop managing workflows for the workload specified by 'request'."
  [request]
  (let [{uuid :uuid} (:body-params request)]
    (logr/infof "POST /api/v1/stop with uuid: %s" uuid)
    (jdbc/with-db-transaction [tx (postgres/wfl-db-config)]
      (->> (workloads/load-workload-for-uuid tx uuid)
           (workloads/stop-workload! tx)
           util/to-edn
           succeed))))

(defn post-exec
  "Create and start workload described in BODY of REQUEST"
  [request]
  (let [workload-request (-> (:body-params request)
                             (rename-keys {:cromwell :executor}))]
    (logr/info "POST /api/v1/exec with request: " workload-request)
    (jdbc/with-db-transaction [tx (postgres/wfl-db-config)]
      (->> (gcs/userinfo request)
           :email
           (assoc workload-request :creator)
           (workloads/execute-workload! tx)
           util/to-edn
           succeed))))

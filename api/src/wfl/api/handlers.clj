(ns wfl.api.handlers
  "Define handlers for API endpoints. Note that pipeline modules MUST be required here."
  (:require [clojure.set :refer [rename-keys]]
            [clojure.tools.logging.readable :as logr]
            [ring.util.http-response :as response]
            [wfl.api.workloads :as workloads]
            [wfl.module.aou :as aou]
            [wfl.module.arrays]
            [wfl.module.copyfile]
            [wfl.module.wgs]
            [wfl.module.xx]
            [wfl.module.sg]
            [wfl.jdbc :as jdbc]
            [wfl.service.google.storage :as gcs]
            [wfl.service.postgres :as postgres]
            [wfl.util :as util]))

(defn succeed
  "A successful response with BODY."
  [body]
  (-> body response/ok (response/content-type "application/json")))

(defn success
  "Respond successfully with BODY."
  [body]
  (constantly (succeed body)))

(defn strip-internals
  "Strip internal properties from the `workload` and its `workflows`."
  [workload]
  (let [prune #(apply dissoc % [:id :items])]
    (prune (update workload :workflows (partial mapv prune)))))

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
    (logr/info "post-create endpoint called: " workload-request)
    (jdbc/with-db-transaction [tx (postgres/wfl-db-config)]
      (->> (assoc workload-request :creator email)
           (workloads/create-workload! tx)
           strip-internals
           succeed))))

(defn get-workload
  "List all workloads or the workload(s) with UUID or PROJECT in REQUEST."
  [request]
  (let [query (get-in request [:parameters :query])]
    (succeed
     (map
      strip-internals
      (jdbc/with-db-transaction [tx (postgres/wfl-db-config)]
        (if-let [uuid (:uuid query)]
          (do (logr/infof "getting workload by uuid %s" uuid)
              [(workloads/load-workload-for-uuid tx uuid)])
          (if-let [prj (:project query)]
            (do (logr/infof "getting workloads by project %s" prj)
                (workloads/load-workloads-with-project tx prj))
            (do (logr/infof "getting all workloads")
                (workloads/load-workloads tx)))))))))

(defn post-start
  "Start the workload with UUID in REQUEST."
  [request]
  (jdbc/with-db-transaction [tx (postgres/wfl-db-config)]
    (let [{uuid :uuid} (:body-params request)]
      (logr/infof "post-start endpoint called: uuid=%s" uuid)
      (-> (workloads/load-workload-for-uuid tx uuid)
          (util/unless-> :started #(workloads/start-workload! tx %))
          strip-internals
          succeed))))

(defn stop-workload
  "Stop a workload, allowing all active processing to complete."
  [request]
  (let [{uuid :uuid} (:body-params request)]
    (logr/infof "stop-workload called workload:%s" uuid)
    (jdbc/with-db-transaction [tx (postgres/wfl-db-config)]
      (->> (workloads/load-workload-for-uuid tx uuid)
           (workloads/stop-workload! tx)
           strip-internals
           succeed))))

(defn post-exec
  "Create and start workload described in BODY of REQUEST"
  [request]
  (let [workload-request (-> (:body-params request)
                             (rename-keys {:cromwell :executor}))]
    (logr/info "post-exec endpoint called: " workload-request)
    (jdbc/with-db-transaction [tx (postgres/wfl-db-config)]
      (->>
       (gcs/userinfo request)
       :email
       (assoc workload-request :creator)
       (workloads/execute-workload! tx)
       strip-internals
       succeed))))

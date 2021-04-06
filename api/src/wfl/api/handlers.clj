(ns wfl.api.handlers
  "Define handlers for API endpoints. Note that pipeline modules MUST be required here."
  (:require [clojure.set                    :refer [rename-keys]]
            [clojure.tools.logging.readable :as logr]
            [ring.util.http-response        :as response]
            [wfl.api.workloads              :as workloads]
            [wfl.module.aou                 :as aou]
            [wfl.module.copyfile]
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
    (postgres/run-tx! #(->> (workloads/load-workload-for-uuid uuid %)
                            (aou/append-to-workload! % notifications)
                            succeed))))

(defn post-create
  "Create the workload described in REQUEST."
  [request]
  (let [workload-request (-> (:body-params request)
                             (rename-keys {:cromwell :executor}))
        {:keys [email]}  (gcs/userinfo request)]
    (logr/info "POST /api/v1/create with request: " workload-request)
    (-> (assoc workload-request :creator email)
        workloads/create-workload!
        strip-internals
        succeed)))

(defn get-workload
  "List all workloads or the workload(s) with UUID or PROJECT in REQUEST."
  [request]
  (let [{:keys [uuid project] :as query} (get-in request [:parameters :query])]
    (logr/info "GET /api/v1/workload with query: " query)
    (->> #(cond uuid    [(workloads/load-workload-for-uuid uuid %)]
                project (workloads/load-workloads-with-project project %)
                :else   (workloads/load-workloads %))
         postgres/run-tx!
         (map strip-internals)
         succeed)))

(defn post-start
  "Start the workload with UUID in REQUEST."
  [request]
  (let [{uuid :uuid} (:body-params request)]
    (logr/infof "POST /api/v1/start with uuid: " uuid)
    (let [{:keys [started] :as workload}
          (postgres/run-tx! (partial workloads/load-workload-for-uuid uuid))]
      (-> workload
          (cond-> started workloads/start-workload!)
          strip-internals
          succeed))))

(defn post-stop
  "Stop managing workflows for the workload specified by 'request'."
  [request]
  (let [{uuid :uuid} (:body-params request)]
    (logr/infof "POST /api/v1/stop with uuid: %s" uuid)
    (-> (partial workloads/load-workload-for-uuid uuid)
        postgres/run-tx!
        workloads/stop-workload!
        strip-internals
        succeed)))

(defn post-exec
  "Create and start workload described in BODY of REQUEST"
  [request]
  (let [workload-request (-> (:body-params request)
                             (rename-keys {:cromwell :executor}))]
    (logr/info "POST /api/v1/exec with request: " workload-request)
    (->> (gcs/userinfo request)
         :email
         (assoc workload-request :creator)
         workloads/execute-workload!
         strip-internals
         succeed)))

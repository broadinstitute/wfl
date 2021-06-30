(ns wfl.tools.endpoints
  (:require [clojure.data.json :as json]
            [clojure.string    :as str]
            [clj-http.client   :as http]
            [wfl.auth          :as auth]
            [wfl.environment   :as env]
            [wfl.util          :as util]))

(defn ^:private wfl-url
  "The WFL server URL to test."
  [& parts]
  (let [url (util/de-slashify (env/getenv "WFL_WFL_URL"))]
    (str/join "/" (cons url parts))))

(defn get-oauth2-id
  "Query oauth2 ID that the server is currently using"
  []
  (-> (http/get (wfl-url "/oauth2id"))
      util/response-body-json
      first))

(defn version
  "Return the WFL Server version."
  []
  (-> (wfl-url "version") http/get util/response-body-json))

(defn get-workload-status
  "Query v1 api for the status of the workload with UUID"
  [uuid]
  (-> (wfl-url "/api/v1/workload")
      (http/get {:headers (auth/get-auth-header) :query-params {:uuid uuid}})
      util/response-body-json
      first))

(defn get-workloads
  "Query v1 api for all workloads"
  []
  (-> (wfl-url "/api/v1/workload")
      (http/get {:headers (auth/get-auth-header)})
      util/response-body-json))

(defn get-workflows
  "Query v1 api for all workflows managed by `_workload`."
  [{:keys [uuid] :as _workload}]
  (-> (wfl-url "/api/v1/workload/" uuid "/workflows")
      (http/get {:headers (auth/get-auth-header)})
      util/response-body-json))

(defn get-workflows-by-status
  "Query v1 api for all workflows managed by `_workload` with a specific status."
  [{:keys [uuid] :as _workload} status]
  (-> (wfl-url "/api/v1/workload/" uuid "/workflows")
      (http/get {:headers (auth/get-auth-header) :query-params {:status status}})
      util/response-body-json))

(defn create-workload
  "Create workload defined by WORKLOAD"
  [workload]
  (-> (wfl-url "/api/v1/create")
      (http/post {:headers      (auth/get-auth-header)
                  :content-type :application/json
                  :body         (json/write-str workload :escape-slash false)})
      util/response-body-json))

(defn start-workload
  "Start processing WORKLOAD. WORKLOAD must be known to the server."
  [workload]
  (let [payload (-> (select-keys workload [:uuid])
                    (json/write-str :escape-slash false))]
    (-> (wfl-url "/api/v1/start")
        (http/post {:headers      (auth/get-auth-header)
                    :content-type :application/json
                    :body         payload})
        util/response-body-json)))

(defn stop-workload
  "Stop processing WORKLOAD. WORKLOAD must be known to the server."
  [workload]
  (let [payload (json/write-str (select-keys workload [:uuid]))]
    (-> (wfl-url "/api/v1/stop")
        (http/post {:headers      (auth/get-auth-header)
                    :content-type :application/json
                    :body         payload})
        util/response-body-json)))

(defn append-to-aou-workload
  "Append SAMPLES to the aou WORKLOAD"
  [samples workload]
  (let [payload (-> (select-keys workload [:uuid])
                    (assoc :notifications samples)
                    (json/write-str :escape-slash false))]
    (-> (wfl-url "/api/v1/append_to_aou")
        (http/post {:headers      (auth/get-auth-header)
                    :content-type :application/json
                    :body         payload})
        util/response-body-json)))

(defn exec-workload
  "Create and start workload defined by `workload-request`."
  [workload-request]
  (let [payload (json/write-str workload-request :escape-slash false)]
    (-> (wfl-url "/api/v1/exec")
        (http/post {:headers      (auth/get-auth-header)
                    :content-type :application/json
                    :body         payload})
        util/response-body-json)))

(defn retry-workflows
  "Retry the workflows in `_workload` by `status`."
  [{:keys [uuid] :as _workload} status]
  (-> (wfl-url "/api/v1/workload/" uuid "/retry")
      (http/post {:headers      (auth/get-auth-header)
                  :content-type :application/json
                  :body         (json/write-str {:status status})})
      util/response-body-json))

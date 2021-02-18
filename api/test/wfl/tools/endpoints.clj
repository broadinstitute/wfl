(ns wfl.tools.endpoints
  (:require [clj-http.client :as client]
            [clojure.data.json :as json]
            [wfl.auth :as auth]
            [wfl.environment :as env]
            [wfl.service.google.storage :as gcs]
            [wfl.util :as util]))

(def userinfo
  (delay (gcs/userinfo {:headers (auth/get-auth-header)})))

(def ^:private wfl-url
  "The WFL server URL to test."
  (delay (env/getenv "WFL_WFL_URL")))

(defn get-oauth2-id
  "Query oauth2 ID that the server is currently using"
  []
  (let [response (client/get (str @wfl-url "/oauth2id"))]
    (first (util/parse-json (:body response)))))

(defn get-workload-status
  "Query v1 api for the status of the workload with UUID"
  [uuid]
  (let [auth-header (auth/get-auth-header)
        response    (client/get (str @wfl-url "/api/v1/workload")
                                {:headers      auth-header
                                 :query-params {:uuid uuid}})]
    (first (util/parse-json (:body response)))))

(defn get-workloads
  "Query v1 api for all workloads"
  []
  (let [response (client/get (str @wfl-url "/api/v1/workload")
                             {:headers (auth/get-auth-header)})]
    (util/parse-json (:body response))))

(defn create-workload
  "Create workload defined by WORKLOAD"
  [workload]
  (let [payload  (json/write-str workload :escape-slash false)
        response (client/post (str @wfl-url "/api/v1/create")
                              {:headers      (auth/get-auth-header)
                               :content-type :json
                               :accept       :json
                               :body         payload})]
    (util/parse-json (:body response))))

(defn start-workload
  "Start processing WORKLOAD. WORKLOAD must be known to the server."
  [workload]
  (let [payload  (-> (select-keys workload [:uuid])
                     (json/write-str :escape-slash false))
        response (client/post (str @wfl-url "/api/v1/start")
                              {:headers      (auth/get-auth-header)
                               :content-type :json
                               :accept       :json
                               :body         payload})]
    (util/parse-json (:body response))))

(defn append-to-aou-workload
  "Append SAMPLES to the aou WORKLOAD"
  [samples workload]
  (let [payload  (-> (select-keys workload [:uuid])
                     (assoc :notifications samples)
                     (json/write-str :escape-slash false))
        response (client/post (str @wfl-url "/api/v1/append_to_aou")
                              {:headers      (auth/get-auth-header)
                               :content-type :json
                               :accept       :json
                               :body         payload})]
    (util/parse-json (:body response))))

(defn exec-workload
  "Create and start workload defined by WORKLOAD"
  [workload-request]
  (let [payload  (json/write-str workload-request :escape-slash false)
        response (client/post (str @wfl-url "/api/v1/exec")
                              {:headers      (auth/get-auth-header)
                               :content-type :json
                               :accept       :json
                               :body         payload})]
    (util/parse-json (:body response))))


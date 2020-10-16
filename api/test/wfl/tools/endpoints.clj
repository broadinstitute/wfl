(ns wfl.tools.endpoints
  (:require [clojure.data.json :as json]
            [clj-http.client :as client]
            [wfl.once :as once]
            [wfl.service.gcs :as gcs]))

(def server
  "The WFL server URL to test."
  (if (System/getenv "WFL_DEPLOY_ENVIRONMENT")
    "https://dev-wfl.gotc-dev.broadinstitute.org"
    "http://localhost:3000"))

(defn parse-json-string
  "Parse the json string STR into a keyword-string map"
  [str]
  (json/read-str str :key-fn keyword))

(def userinfo
  (delay (gcs/userinfo {:headers (once/get-auth-header)})))

(defn get-oauth2-id
  "Query oauth2 ID that the server is currently using"
  []
  (let [response (client/get (str server "/oauth2id"))]
    (first (parse-json-string (:body response)))))

(defn get-workload-status
  "Query v1 api for the status of the workload with UUID"
  [uuid]
  (let [auth-header (once/get-auth-header)
        response    (client/get (str server "/api/v1/workload")
                                {:headers      auth-header
                                 :query-params {:uuid uuid}})]
    (first (parse-json-string (:body response)))))

(defn get-workloads
  "Query v1 api for all workloads"
  []
  (let [auth-header (once/get-auth-header)
        response    (client/get (str server "/api/v1/workload")
                                {:headers auth-header})]
    (parse-json-string (:body response))))

(defn create-workload
  "Create workload defined by WORKLOAD"
  [workload]
  (let [auth-header (once/get-auth-header)
        payload     (json/write-str workload :escape-slash false)
        response    (client/post (str server "/api/v1/create")
                                 {:headers      auth-header
                                  :content-type :json
                                  :accept       :json
                                  :body         payload})]
    (parse-json-string (:body response))))

(defn start-workload
  "Start processing WORKLOAD. WORKLOAD must be known to the server."
  [workload]
  (let [auth-header (once/get-auth-header)
        payload     (json/write-str [workload] :escape-slash false)
        response    (client/post (str server "/api/v1/start")
                                 {:headers      auth-header
                                  :content-type :json
                                  :accept       :json
                                  :body         payload})]
    (first (parse-json-string (:body response)))))

(defn append-to-aou-workload
  "Append SAMPLES to the aou WORKLOAD"
  [samples workload]
  (let [auth-header (once/get-auth-header)
        payload     (->
                      (select-keys workload [:cromwell :uuid])
                      (assoc :notifications samples :environment :aou-dev)
                      (json/write-str :escape-slash false))
        response    (client/post (str server "/api/v1/append_to_aou")
                                 {:headers      auth-header
                                  :content-type :json
                                  :accept       :json
                                  :body         payload})]
    (parse-json-string (:body response))))

(defn start-wgs-workflow
  "Submit the WGS Reprocessing WORKFLOW"
  [workflow]
  (let [auth-header (once/get-auth-header)
        payload     (json/write-str workflow :escape-slash false)
        response    (client/post (str server "/api/v1/wgs")
                                 {:headers      auth-header
                                  :content-type :json
                                  :accept       :json
                                  :body         payload})]
    (parse-json-string (:body response))))

(defn exec-workload
  "Create and start workload defined by WORKLOAD"
  [workload]
  (let [auth-header (once/get-auth-header)
        payload     (json/write-str workload :escape-slash false)
        response    (client/post (str server "/api/v1/exec")
                                 {:headers      auth-header
                                  :content-type :json
                                  :accept       :json
                                  :body         payload})]
    (parse-json-string (:body response))))

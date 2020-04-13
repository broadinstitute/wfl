(ns zero.api.handlers
  "Define handlers for API endpoints"
  (:require [clojure.data.json     :as json]
            [clojure.java.jdbc     :as jdbc]
            [clojure.string        :as str]
            [ring.util.response    :as response]
            [zero.module.aos       :as aos]
            [zero.module.wgs       :as wgs]
            [zero.service.cromwell :as cromwell]
            [zero.service.postgres :as postgres]
            [zero.util             :as util]
            [zero.zero             :as zero])
  (:import [java.util Base64]))

(def oauth2-profiles
  "OAuth2 profiles for the ring wrapper."
  (let [id           "OAUTH2_CLIENT_ID"
        secret       "OAUTH2_CLIENT_SECRET"
        launch-uri   "/auth/google"]
    {:google {:access-token-uri "https://www.googleapis.com/oauth2/v4/token"
              :authorize-uri    "https://accounts.google.com/o/oauth2/v2/auth"
              :client-id        (util/getenv id id)
              :client-secret    (util/getenv secret secret)
              :landing-uri      "/"
              :launch-uri       launch-uri
              :redirect-uri     (str launch-uri "/callback")
              :scopes           ["email" "openid"]}}))

(defn base64-url-decode-json
  "URL-safe decode B64S and parse it as JSON."
  [^String b64s]
  (-> (.decode (Base64/getUrlDecoder) b64s)
      String.
      (json/read-str :key-fn keyword)))

(defn decode-jwt
  "Parse and decode the JSON Web TOKEN (JWT)."
  [token]
  (let [[header payload signature] (str/split token #"\.")]
    {:header    (base64-url-decode-json header)
     :payload   (base64-url-decode-json payload)
     :signature signature}))

(defn authorize
  "Add JWT to REQUEST when it contains a broadinstitute.org account.
  Otherwise return a 401 Unauthorized response."
  [handler]
  (fn [request]
    (letfn [(valid? [{:keys [payload] :as jwt}]
              (let [{:keys [aud exp hd iss]} payload]
                (and (= hd "broadinstitute.org")
                     (= iss "https://accounts.google.com")
                     (= aud (get-in oauth2-profiles [:google :client-id]))
                     (> exp (quot (System/currentTimeMillis) 1000))
                     jwt)))
            (token [request]
              (get-in request [:oauth2/access-tokens :google :id-token]
                      (some-> request
                              (response/get-header "authorization")
                              (str/split #" ")
                              last)))]
      (if-let [jwt (some-> request token decode-jwt valid?)]
        (handler (assoc request :jwt jwt))
        (-> (response/response {:response {:message "Unauthorized"}})
            (response/header "WWW-Authenticate" "Bearer realm=API access")
            (response/content-type "application/json")
            (response/status 401))))))

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

(defn create-fail
  "Fail this request returning BODY as result."
  [body]
  (fail {:create-workload-failed body}))

(defn post-workload
  "Create the workload described in BODY of REQUEST."
  [{:keys [parameters] :as request}]
  (let [{:keys [body]} parameters
        create {"AllOfUsArrays"                   aos/create-workload
                "ExternalWholeGenomeReprocessing" wgs/create-workload}]
    (succeed ((create (:pipeline body) create-fail) body))))

(defn get-workload
  "List workloads or workload with UUID."
  [request]
  (let [environment (keyword (util/getenv "ENVIRONMENT" "debug"))]
    (->> (if-let [uuid (get-in request [:parameters :query :uuid])]
           ["SELECT * FROM workload WHERE uuid = ?" uuid]
           ["SELECT * FROM workload"])
         (jdbc/query (postgres/zero-db-config environment))
         (map (fn [wl] (into {} (filter second wl))))
         succeed)))

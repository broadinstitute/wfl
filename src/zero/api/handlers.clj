(ns zero.api.handlers
  "Define handlers for API endpoints"
  (:require [clojure.data.json     :as json]
            [clojure.java.jdbc     :as jdbc]
            [clojure.string        :as str]
            [ring.util.response    :as response]
            [zero.module.aou       :as aou]
            [zero.module.wgs       :as wgs]
            [zero.module.wl        :as wl]
            [zero.service.cromwell :as cromwell]
            [zero.service.postgres :as postgres]
            [zero.util             :as util]
            [zero.zero             :as zero])
  (:import [java.util Base64]))

(def oauth2-profiles
  "OAuth2 profiles for the ring wrapper."
  (let [id         "OAUTH2_CLIENT_ID"
        secret     "OAUTH2_CLIENT_SECRET"
        launch-uri "/auth/google"]
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

(defn add-fail
  "Fail this request returning BODY as result."
  [body]
  (fail {:add-workload-failed body}))

(defn post-workload
  "Create the workload described in BODY of REQUEST."
  [{:keys [parameters] :as request}]
  (let [environment (keyword (util/getenv "ENVIRONMENT" "debug"))
        {:keys [body]} parameters
        add {"AllOfUsArrays"                   aou/add-workload!
             "ExternalWholeGenomeReprocessing" wl/add-workload!}
        add! (add (:pipeline body) add-fail)]
    (->> body
         (add! (postgres/zero-db-config environment))
         (conj ["SELECT * FROM workload WHERE uuid = ?"])
         (jdbc/query (postgres/zero-db-config environment))
         first
         (filter second)
         (into {})
         succeed)))

(defn get-workload
  "List workloads or workload with UUID in REQUEST."
  [request]
  (let [environment (keyword (util/getenv "ENVIRONMENT" "debug"))]
    (letfn [(unnilify [m] (into {} (filter second m)))
            (workflows [tx workload]
              (->> workload :load
                   (format "SELECT * FROM %s")
                   (jdbc/query tx)
                   (map unnilify)
                   (assoc workload :load)))]
      (jdbc/with-db-transaction [tx (postgres/zero-db-config environment)]
        (->> (if-let [uuid (get-in request [:parameters :query :uuid])]
               ["SELECT * FROM workload WHERE uuid = ?" uuid]
               ["SELECT * FROM workload"])
             (jdbc/query tx)
             (map unnilify)
             (map (partial workflows tx))
             doall
             succeed)))))

(defn post-start
  "Start the workloads with UUIDs in BODY of REQUEST."
  [{:keys [parameters] :as request}]
  (zero.debug/trace request)
  (let [db    (-> "ENVIRONMENT"
                  (util/getenv "debug")
                  keyword
                  postgres/zero-db-config)
        start {"AllOfUsArrays"                   aou/start-workload!
               "ExternalWholeGenomeReprocessing" wl/start-workload!}]
    (letfn [(q [[left right]] (fn [it] (str left it right)))
            (start! [{:keys [pipeline] :as wl}] ((start pipeline) db wl))]
      (->> parameters :body distinct
           (map (q "''")) (str/join ",") ((q "()"))
           (format "SELECT * FROM workload WHERE uuid in %s")
           (jdbc/query db)
           zero.debug/trace
           (map start!)
           zero.debug/trace
           succeed))))

(comment
  (do (def uuids ["d7f86861-289c-4b3d-844c-9a0d3cb1db4f"
                  "994f593f-b391-42a2-bb7d-945f007e99da"
                  "ccf15e2b-788b-4bc9-8e11-1361480873c9"
                  "3718dcb6-ea74-4f77-861e-0519bf2b0c6a"
                  "5ccd690f-eb91-4d2b-a359-f81fd723de4e"
                  "88107c7a-1f18-448a-87a4-5ea95b33fc3f"
                  "871d60ab-268a-4d3e-9b78-88e52283d787"
                  "b403dda2-f9f0-4e30-a95c-d3a907a68a51"])
      (def body uuids)
      (def parameters {:body body})
      (def request {:parameters parameters}))
  (post-start request)
  (post-start {:parameters
               {:body
                ["ccf15e2b-788b-4bc9-8e11-1361480873c9"]}})
  )

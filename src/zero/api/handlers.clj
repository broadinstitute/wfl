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

(defn get-workload-for-uuid
  "Use transaction TX to return workload with UUID."
  [tx {:keys [uuid]}]
  (letfn [(unnilify [m] (into {} (filter second m)))]
    (let [select   ["SELECT * FROM workload WHERE uuid = ?" uuid]
          workload (unnilify (first (jdbc/query tx select)))]
      (assoc workload :workflows
             (->> workload :load
                  (format "SELECT * FROM %s")
                  (jdbc/query tx)
                  (mapv unnilify))))))

(defn get-workload
  "List all workloads or the workload with UUID in REQUEST."
  [request]
  (let [environment (keyword (util/getenv "ENVIRONMENT" "debug"))]
    (jdbc/with-db-transaction [tx (postgres/zero-db-config environment)]
      (->> (if-let [uuid (get-in request [:parameters :query :uuid])]
             [{:uuid uuid}]
             (jdbc/query tx ["SELECT uuid FROM workload"]))
           (mapv (partial get-workload-for-uuid tx))
           succeed))))

(defn post-start
  "Start the workloads with UUIDs in REQUEST."
  [request]
  (let [start {"AllOfUsArrays"                   aou/start-workload!
               "ExternalWholeGenomeReprocessing" wl/start-workload!}
        env   (keyword (util/getenv "ENVIRONMENT" "debug"))
        uuids (-> request :parameters :body distinct)]
    (letfn [(q [[left right]] (fn [it] (str left it right)))
            (start! [tx {:keys [pipeline] :as workload}]
              ((start pipeline) tx workload))]
      (jdbc/with-db-transaction [tx (postgres/zero-db-config env)]
        (->> uuids
             (map :uuid)
             (map (q "''")) (str/join ",") ((q "()"))
             (format "SELECT * FROM workload WHERE uuid in %s")
             (jdbc/query tx)
             (run! (partial start! tx)))
        (->> uuids
             (mapv (partial get-workload-for-uuid tx))
             succeed)))))

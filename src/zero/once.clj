(ns zero.once
  "Manage credentials."
  (:require [clojure.data.json :as json]
            [clojure.java.io   :as io]
            [clojure.string    :as str]
            [zero.environments :as env]
            [zero.util         :as util]
            [zero.zero         :as zero])
  (:import [com.google.auth.oauth2 GoogleCredentials ServiceAccountCredentials]))

(defn- authorization-header-with-bearer-token
  "An Authorization header with a Bearer TOKEN."
  [token]
  {"Authorization" (str/join \space ["Bearer" token])})

(defn- get-service-account-from-environment
  "Throw or get the service account source from ZERO_DEPLOY_ENVIRONMENT."
  []
  (let [environment (util/getenv "ZERO_DEPLOY_ENVIRONMENT" "debug")
        env (zero/throw-or-environment-keyword! environment)]
    (get-in env/stuff [env :server :service-account])))

(defn- service-account-credentials
  "Throw or return credentials for the service account SA."
  [{:keys [file vault] :as sa}]
  (letfn [(jsonify [edn] (json/write-str edn :escape-slash false))]
    (-> (cond file  (io/file file)
              vault (-> vault util/vault-secrets jsonify .getBytes)
              :else (throw (IllegalArgumentException. (pr-str sa))))
      io/input-stream GoogleCredentials/fromStream
      (.createScoped ["https://www.googleapis.com/auth/cloud-platform"
                      "https://www.googleapis.com/auth/userinfo.email"
                      "https://www.googleapis.com/auth/userinfo.profile"]))))

(defn- service-account-token
  "Throw or return a bearer token for the service account SA."
  [sa]
  (-> sa service-account-credentials .refreshAccessToken .getTokenValue))

(defn get-auth-header
  "An Authorization header with a Bearer token."
  []
  (authorization-header-with-bearer-token
    (if-let [environment (util/getenv "ZERO_DEPLOY_ENVIRONMENT")]
      (let [env (zero/throw-or-environment-keyword! environment)
            sa (get-in env/stuff [env :server :service-account])]
        (service-account-token sa))
      (util/shell! "gcloud" "auth" "print-access-token"))))

(defn get-service-account-header
  "An Authorization header with service account Bearer token."
  []
  (authorization-header-with-bearer-token
    (service-account-token
      (get-service-account-from-environment))))

(defn service-account-email
  "The client_email for the ZERO_DEPLOY_ENVIRONMENT service account."
  []
  (-> (get-service-account-from-environment)
    service-account-credentials .getClientEmail))

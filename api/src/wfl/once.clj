(ns wfl.once
  "Manage credentials."
  (:require [clojure.data.json :as json]
            [clojure.java.io   :as io]
            [clojure.string    :as str]
            [wfl.environments :as env]
            [wfl.util         :as util]
            [wfl.wfl         :as wfl])
  (:import [com.google.auth.oauth2 GoogleCredentials ServiceAccountCredentials]))

(defn authorization-header-with-bearer-token
  "An Authorization header with a Bearer TOKEN."
  [token]
  {"Authorization" (str/join \space ["Bearer" token])})

(defn service-account-credentials
  "Throw or return credentials for the WFL service account."
  []
  (some->
    (if-let [file (util/getenv "GOOGLE_APPLICATION_CREDENTIALS")]
      (-> file io/file)
      (-> "WFL_DEPLOY_ENVIRONMENT"
        (util/getenv "debug")
        wfl/error-or-environment-keyword
        env/stuff :server :service-account util/vault-secrets
        (json/write-str :escape-slash false)
        .getBytes))
    io/input-stream GoogleCredentials/fromStream
    (.createScoped ["https://www.googleapis.com/auth/cloud-platform"
                    "https://www.googleapis.com/auth/userinfo.email"
                    "https://www.googleapis.com/auth/userinfo.profile"])))

(defn service-account-email
  "The client_email for the WFL service account."
  []
  (.getClientEmail (service-account-credentials)))

(defn service-account-token
  "A bearer token for the WFL service account."
  []
  (-> (service-account-credentials) .refreshAccessToken .getTokenValue))

(defn get-service-account-header
  "An Authorization header with a service account Bearer token."
  []
  (authorization-header-with-bearer-token (service-account-token)))

(defn get-auth-header
  "An Authorization header with a Bearer token."
  []
  (authorization-header-with-bearer-token
    (if (util/getenv "WFL_DEPLOY_ENVIRONMENT")
      (service-account-token)
      (util/shell! "gcloud" "auth" "print-access-token"))))

(defn- read-oauth-client-id
  "Actively read the OAuth client ID from vault"
  []
  (-> "WFL_DEPLOY_ENVIRONMENT"
      (util/getenv "debug")
      wfl/error-or-environment-keyword
      env/stuff
      :server
      :vault
      util/vault-secrets
      :oauth2_client_id))

(def return-oauth-client-id
  "Memoize the reading of the OAuth client ID to be more responsive"
  (memoize read-oauth-client-id))

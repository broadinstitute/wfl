(ns wfl.auth
  "Manage credentials and configurations."
  (:require [clojure.java.io   :as io]
            [clojure.string    :as str]
            [wfl.environment :as env]
            [wfl.util         :as util])
  (:import [com.google.auth.oauth2 GoogleCredentials]))

(defn ^:private authorization-header-with-bearer-token
  "An Authorization header with a Bearer TOKEN."
  [token]
  {"Authorization" (str/join \space ["Bearer" token])})

(defn ^:private service-account-credentials
  "Nil or credentials for WFL from the environment."
  []
  (some-> (env/getenv "GOOGLE_APPLICATION_CREDENTIALS")
          io/input-stream GoogleCredentials/fromStream
          (.createScoped ["https://www.googleapis.com/auth/cloud-platform"
                          "https://www.googleapis.com/auth/userinfo.email"
                          "https://www.googleapis.com/auth/userinfo.profile"])))

(defn service-account-email
  "The client_email for the WFL service account."
  []
  (.getClientEmail (service-account-credentials)))

(defn ^:private service-account-token
  "Nil or a bearer token for the WFL service account."
  []
  (-> (service-account-credentials) .refreshAccessToken .getTokenValue))

(defn get-service-account-header
  "An Authorization header with a service account Bearer token."
  []
  (authorization-header-with-bearer-token (service-account-token)))

(defn get-auth-header
  "An Authorization header with a Bearer token, from service account file
   or from gcloud command on behalf of you."
  []
  (authorization-header-with-bearer-token
   (or (service-account-token)
       (util/shell! "gcloud" "auth" "print-access-token"))))

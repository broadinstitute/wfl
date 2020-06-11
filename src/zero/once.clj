(ns zero.once
  "Manage credentials."
  (:require [clojure.data.json :as json]
            [clojure.java.io   :as io]
            [clojure.string    :as str]
            [zero.environments :as env]
            [zero.util         :as util]
            [zero.zero         :as zero])
  (:import [com.google.auth.oauth2 GoogleCredentials ServiceAccountCredentials]))

(defn authorization-header-with-bearer-token
  "An Authorization header with a Bearer TOKEN."
  [token]
  {"Authorization" (str/join \space ["Bearer" token])})

(defn service-account-token
  "Throw or return a bearer token for the service account SA."
  [{:keys [file vault] :as sa}]
  (-> (cond file  (io/file file)
            vault (-> vault util/vault-secrets json/write-str .getBytes)
            :else (throw (IllegalArgumentException. (pr-str sa))))
    io/input-stream GoogleCredentials/fromStream
    (.createScoped ["https://www.googleapis.com/auth/cloud-platform"
                    "https://www.googleapis.com/auth/userinfo.email"
                    "https://www.googleapis.com/auth/userinfo.profile"])
    .refreshAccessToken .getTokenValue))

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
    (let [environment (util/getenv "ZERO_DEPLOY_ENVIRONMENT" "debug")
          env (zero/throw-or-environment-keyword! environment)
          sa (get-in env/stuff [env :server :service-account])]
      (service-account-token sa))))

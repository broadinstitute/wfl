(ns zero.once
  "Manage some credentials."
  (:require [clojure.data.json :as json]
            [clojure.java.io   :as io]
            [zero.debug]
            [zero.environments :as env]
            [zero.util         :as util]
            [zero.zero         :as zero])
  (:import [com.google.auth.oauth2 ServiceAccountCredentials UserCredentials]))

;; This is evil.
;;
(defn user-credentials
  "NIL or new UserCredentials for caller. "
  []
  (some-> ["gcloud" "info" "--format=json"]
    (->> (apply util/shell!))
    (json/read-str :key-fn keyword)
    :config :paths :global_config_dir
    (str "/" "application_default_credentials.json")
    io/input-stream
    UserCredentials/fromStream
    util/do-or-nil))

(defn service-account-credentials
  "Google service account credentials from FILE."
  [^String file]
  (let [scopes ["https://www.googleapis.com/auth/cloud-platform"]]
    (-> file io/input-stream
      ServiceAccountCredentials/fromStream
      (.createScoped scopes))))

(defn get-auth-header
  "An valid auth header valid in the ZERO_DEPLOY_ENVIRONMENT."
  []
  (util/bearer-token-header-for
    (if-let [environment (util/getenv "ZERO_DEPLOY_ENVIRONMENT")]
      (let [env  (zero/throw-or-environment-keyword! environment)]
        (when-let [path (get-in env/stuff [env :server :service-account])]
          (service-account-credentials path)))
      (user-credentials))))

(defn get-service-account-header
  "Nil or an auth header with service account credentials."
  []
  (let [environment (util/getenv "ZERO_DEPLOY_ENVIRONMENT" "debug")
        env  (zero/throw-or-environment-keyword! environment)]
    (when-let [path (get-in env/stuff [env :server :service-account])]
      (util/bearer-token-header-for (service-account-credentials path)))))

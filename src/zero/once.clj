(ns zero.once
  "Low-level vars to define exactly once and auth related junk."
  (:require [clojure.data.json :as json]
            [clojure.java.io   :as io]
            [zero.environments :as env]
            [zero.util         :as util]
            [zero.zero         :as zero])
  (:import [com.google.auth.oauth2 GoogleCredentials UserCredentials]
           [java.net URI]))

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
  (-> file io/input-stream
      GoogleCredentials/fromStream
      (.createScoped ["https://www.googleapis.com/auth/cloud-platform"])))

(defn get-auth-header!
  "Return a valid auth header. Refresh and generate the access
   token with gcloud command if invoked from command line,
   generate the access token from service account if invoked
   from a live server."
  []
  (util/bearer-token-header-for
    (if-let [environment (System/getenv "ZERO_DEPLOY_ENVIRONMENT")]
      (let [env  (zero/throw-or-environment-keyword! environment)
            path (get-in env/stuff [env :cromwell :service-account])]
        (service-account-credentials path))
      (user-credentials))))

(defn get-service-auth-header
  "Nil or an 'Authorization: Bearer <token>' header for SERVICE."
  [service]
  (when-let [environment "debug" #_(System/getenv "ZERO_DEPLOY_ENVIRONMENT")]
    (let [env  (zero/throw-or-environment-keyword! environment)]
      (when-let [path (get-in env/stuff [env service :service-account])]
        (util/bearer-token-header-for (service-account-credentials path))))))

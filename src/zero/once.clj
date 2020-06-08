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

(defn get-auth-header
  "Nil or a valid auth header."
  []
  (util/bearer-token-header-for
    (if-let [environment (System/getenv "ZERO_DEPLOY_ENVIRONMENT")]
      (let [env  (zero/throw-or-environment-keyword! environment)]
        (when-let [path (get-in env/stuff [env :server :service-account])]
          (service-account-credentials path)))
      (user-credentials))))

(defn get-service-account-header
  "Nil or an 'Authorization: Bearer <token>' header."
  []
  (when-let [environment "debug" #_(System/getenv "ZERO_DEPLOY_ENVIRONMENT")]
    (let [env  (zero/throw-or-environment-keyword! environment)]
      (when-let [path (get-in env/stuff [env :server :service-account])]
        (zero.debug/trace path)
        (util/bearer-token-header-for (service-account-credentials path))))))

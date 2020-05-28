(ns zero.once
  "Low-level vars to define exactly once and auth related junk."
  (:require [clojure.data.json  :as json]
            [zero.util          :as util]
            [zero.environments  :as env]
            [zero.zero          :as zero])
  (:import [com.google.auth.oauth2 UserCredentials]
           [java.net URI]
           (java.io FileInputStream)
           (com.google.auth.oauth2 GoogleCredentials)))

;; https://developers.google.com/identity/protocols/OAuth2InstalledApp#refresh
;;
(defn user-credentials
  "NIL or new UserCredentials for call. "
  []
  (when-let [out (->> ["gcloud" "auth" "print-access-token" "--format=json"]
                      (apply util/shell!)
                      util/do-or-nil)]
    (let [{:strs [client_id client_secret refresh_token token_uri]}
          (json/read-str out)]
      (when (and client_id client_secret refresh_token token_uri)
        (.build (doto (UserCredentials/newBuilder)
                  (.setClientId client_id)
                  (.setClientSecret client_secret)
                  (.setRefreshToken refresh_token)
                  (.setTokenServerUri (new URI token_uri))))))))

(defn service-account-credentials
  "Generate scoped GoogleCredentials from a service account FILE."
  [^String file]
  (when file
    (let [scopes ["email" "profile" "openid"]
          credentials (GoogleCredentials/fromStream (FileInputStream. file))]
      (.createScoped credentials scopes))))

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

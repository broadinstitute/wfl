(ns zero.once
  "Low-level vars to define exactly once and auth related junk."
  (:require [clojure.data.json  :as json]
            [zero.util          :as util]
            [zero.environments  :as env])
  (:import [com.google.auth.oauth2 UserCredentials]
           [java.net URI]
           (java.io FileInputStream)
           (com.google.auth.oauth2 GoogleCredentials)))

;; https://developers.google.com/identity/protocols/OAuth2InstalledApp#refresh
;;
(defn new-user-credentials
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

;; The re-usable credentials object for token generation
;;
(defonce the-cached-credentials (delay (new-user-credentials)))

(defn get-local-auth-header
  "Return a valid auth header, refresh the access token
  under the hood if necessary."
  []
  (util/bearer-token-header-for @the-cached-credentials))

(defn new-credentials-from-service-account
  "Generate scoped GoogleCredentials from a service account FILE."
  [^String file]
  (when-not (nil? file)
    (let [scopes ["email" "profile" "openid"]
          credentials (GoogleCredentials/fromStream (FileInputStream. file))]
      (.createScoped credentials scopes))))

(defn service-account-per-env
  "Map of environment to service account path."
  []
  (letfn [(get-sa [environment] {(keyword environment)
                                 (get-in env/stuff [(keyword environment) :project-service-account])})]
    (into {} (map get-sa env/registered-envs))))

(defn update-map-vals
  "Map F over every value of M, with the keys remain the same."
  [f m]
  (reduce-kv (fn [m k v] (assoc m k (f v))) {} m))

;; The re-usable environment:credentials-object map for token generation and refreshing
;;
(defonce the-cached-credentials-from-service-account
         (delay (update-map-vals new-credentials-from-service-account (service-account-per-env))))

(comment
  (some-> (:xx @the-cached-credentials-from-service-account)
          .refreshAccessToken
          .getTokenValue)
  (let [envs {:wgs-dev "some-test-service-account.json"
              :xx      "some-test-service-account.json"}]
    (update-map-vals new-credentials-from-service-account envs))
  )

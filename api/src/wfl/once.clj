(ns wfl.once
  "Manage credentials and configurations."
  (:require [clojure.data.json :as json]
            [clojure.java.io   :as io]
            [clojure.string    :as str]
            [clojure.tools.logging :as log]
            [wfl.environment :as env]
            [vault.client.http]         ; vault.core needs this
            [vault.core :as vault]
            [wfl.util         :as util])
  (:import [com.google.auth.oauth2 GoogleCredentials]))

;; Only load the relevant environment variables
;;
(defonce the-system-environments
  (delay (reduce #(assoc %1 %2 (System/getenv %2)) {}
                 ["COOKIE_SECRET"
                  "CROMWELL"
                  "GOOGLE_APPLICATION_CREDENTIALS"
                  "USER"
                  "VAULT_TOKEN"
                  "WFL_OAUTH2_CLIENT_ID"
                  "WFL_POSTGRES_URL"
                  "WFL_POSTGRES_USERNAME"
                  "WFL_POSTGRES_PASSWORD"
                  "WFL_DEPLOY_ENVIRONMENT"])))

(defn vault-secrets
  "Return the secrets at `path` in vault."
  [path]
  (let [token (or (->> [(System/getProperty "user.home") ".vault-token"]
                       (str/join "/") slurp util/do-or-nil)
                  (@the-system-environments "VAULT_TOKEN"))]
    (try (vault/read-secret
          (doto (vault/new-client "https://clotho.broadinstitute.org:8200/")
            (vault/authenticate! :token token))
          path {})
         (catch Throwable e
           (log/warn e "Issue with Vault")
           (log/debug "Perhaps run 'vault login' and try again")))))

(defn authorization-header-with-bearer-token
  "An Authorization header with a Bearer TOKEN."
  [token]
  {"Authorization" (str/join \space ["Bearer" token])})

;; FIXME: it seems we have to use dev and prod
(defn service-account-credentials
  "Throw or return credentials for the WFL from service account file or vault."
  []
  (some->
   (if-let [sa-file (@the-system-environments "GOOGLE_APPLICATION_CREDENTIALS")]
     (-> sa-file io/file)
     (-> (get-in env/stuff [:debug :server :service-account])
         vault-secrets
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
  "An Authorization header with a Bearer token, from service account file
   or from gcloud command on behalf of you."
  []
  (authorization-header-with-bearer-token
   (if (@the-system-environments "WFL_DEPLOY_ENVIRONMENT")
     (service-account-token)
     (util/shell! "gcloud" "auth" "print-access-token"))))

(def oauth-client-id
  "The client ID based on, in order, the environment variable, vault, or the gotc-dev one"
  (delay (or (not-empty (@the-system-environments "WFL_OAUTH2_CLIENT_ID"))
             (util/do-or-nil-silently
              (-> :debug
                  env/stuff :server :vault
                  vault-secrets :oauth2_client_id)))))

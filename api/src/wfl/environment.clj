(ns wfl.environment
  "Map environment to various values here."
  (:require [clojure.data.json :as json]
            [clojure.tools.logging :as log]
            [clojure.string :as str]
            [vault.client.http] ; vault.core needs this
            [vault.core :as vault]))

(declare getenv)

(defn ^:private vault-secrets
  "Return the secrets at `path` in vault."
  [path]
  (let [token (or (getenv "VAULT_TOKEN")
                  (->> [(System/getProperty "user.home") ".vault-token"]
                       (str/join "/")
                       slurp))]
    (vault/read-secret
     (doto (vault/new-client "https://clotho.broadinstitute.org:8200/")
       (vault/authenticate! :token token))
     path {})))

;; Keep this map pure - defer any IO to the thunk returned by this map.
(def ^:private defaults
  "Default actions (thunks) for computing environment variables, mainly for
   development, testing and documentation purposes."
  {"GOOGLE_APPLICATION_CREDENTIALS"
   #(-> "secret/dsde/gotc/dev/wfl/wfl-non-prod-service-account.json"
        vault-secrets
        (json/write-str :escape-slash false)
        .getBytes)
   "WFL_CLIO_URL"
   #(-> "https://clio.gotc-dev.broadinstitute.org")
   "WFL_COOKIE_SECRET"
   #(-> "secret/dsde/gotc/dev/zero" vault-secrets :cookie_secret)
   "WFL_TDR_URL"
   #(-> "https://data.terra.bio")
   "WFL_OAUTH2_CLIENT_ID"
   #(-> "secret/dsde/gotc/dev/zero" vault-secrets :oauth2_client_id)
   "WFL_POSTGRES_PASSWORD"
   #(-> "password")
   "WFL_POSTGRES_URL"
   #(-> "jdbc:postgresql:wfl")
   "WFL_POSTGRES_USERNAME"
   #(-> nil)
   "WFL_FIRECLOUD_URL"
   #(-> "https://api.firecloud.org")
   "WFL_RAWLS_URL"
   #(-> "https://rawls.dsde-dev.broadinstitute.org")

   ;; -- variables used in test code below this line --
   "WFL_CROMWELL_URL"
   #(-> "https://cromwell-gotc-auth.gotc-dev.broadinstitute.org")
   "WFL_TDR_DEFAULT_PROFILE"
   #(-> "6370f5a1-d777-4991-8200-ceab83521d43")
   "WFL_WFL_URL"
   #(-> "https://dev-wfl.gotc-dev.broadinstitute.org")})

(def ^:private __getenv
  (memoize #(or (System/getenv %) (when-let [init (defaults %)] (init)))))

(def testing
  "Override the environment used by `getenv` for testing. Use
  `wfl.tools.fixtures/with-temporary-environment` instead of this."
  (atom {}))

(defn getenv
  "Lookup the value of the environment variable specified by `name`."
  [name]
  (log/debugf "Reading environment variable %s" name)
  (or (@testing name) (__getenv name)))

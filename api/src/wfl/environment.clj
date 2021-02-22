(ns wfl.environment
  "Map environment to various values here."
  (:require [clojure.data.json  :as json]
            [clojure.tools.logging :as log]
            [clojure.string :as str]
            [vault.client.http]         ; vault.core needs this
            [vault.core :as vault]
            [wfl.util  :as util]))

;; TODO: `is-known-cromwell-url?` in modules means new projects require new releases
;;  since this is baked in code. Can we improve this?

(defn ^:private vault-secrets
  "Return the secrets at `path` in vault."
  [path]
  (let [token (or (->> [(System/getProperty "user.home") ".vault-token"]
                       (str/join "/") slurp util/do-or-nil)
                  (System/getenv "VAULT_TOKEN"))]
    (try (vault/read-secret
          (doto (vault/new-client "https://clotho.broadinstitute.org:8200/")
            (vault/authenticate! :token token))
          path {})
         (catch Throwable e
           (log/warn e "Issue with Vault")
           (log/debug "Perhaps run 'vault login' and try again")))))

(def ^:private defaults
  "Default values for environment variables, mainly for dev purposes.
   Hide values behind thunks to avoid compile time I/O.
   Missing defaults here can lead to NPE exceptions."
  {"GOOGLE_APPLICATION_CREDENTIALS"
   #(-> "secret/dsde/gotc/dev/wfl/wfl-non-prod-service-account.json"
        vault-secrets
        (json/write-str :escape-slash false)
        .getBytes)
   "WFL_CLIO_URL"
   #(-> "https://clio.gotc-dev.broadinstitute.org")
   "WFL_COOKIE_SECRET"
   #(-> "secret/dsde/gotc/dev/zero" vault-secrets :cookie_secret)
   "WFL_CROMWELL_URL"
   #(-> "https://cromwell-gotc-auth.gotc-dev.broadinstitute.org")
   "WFL_DATA_REPO_SA"
   #(-> "jade-k8-sa@broad-jade-dev.iam.gserviceaccount.com")
   "WFL_OAUTH2_CLIENT_ID"
   #(-> "secret/dsde/gotc/dev/zero" vault-secrets :oauth2_client_id)
   "WFL_POSTGRES_PASSWORD"
   #(-> "password")
   "WFL_POSTGRES_URL"
   #(-> "jdbc:postgresql:wfl")
   "WFL_POSTGRES_USERNAME"
   #(-> nil)
   "WFL_TERRA_DATA_REPO_URL"
   #(-> "https://jade.datarepo-dev.broadinstitute.org/")
   "WFL_FIRECLOUD_URL"
   (constantly "https://api.firecloud.org/")
   "WFL_WFL_URL"
   #(-> "https://dev-wfl.gotc-dev.broadinstitute.org")})

(def ^:private __getenv
  (memoize (fn [name] (or (System/getenv name) ((defaults name))))))

(def testing "Override the environment for testing" (atom {}))

(defn getenv [name] (or (@testing name) (__getenv name)))

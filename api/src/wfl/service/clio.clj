(ns wfl.clio
  "Talk to Clio for some reason ..."
  (:require [clojure.java.io :as io]
            [clojure.data.json :as json]
            [clojure.string :as str]
            [clj-http.client :as http]
            [wfl.environments :as env]
            [wfl.once :as once]
            [wfl.util :as util]
            [wfl.wfl :as wfl])
  (:import [com.google.auth.oauth2 GoogleCredentials]))

(def deploy-environment
  "Where is WFL deployed?"
  (-> "WFL_DEPLOY_ENVIRONMENT"
      (util/getenv "debug")
      wfl/error-or-environment-keyword env/stuff))

(defn get-authorization-header
  "An Authorization header for talking to Clio where deployed."
  []
  (with-open [in (-> deploy-environment
                     :google_account_vault_path util/vault-secrets
                     (json/write-str :escape-slash false)
                     .getBytes io/input-stream)]
    (-> in GoogleCredentials/fromStream
        (.createScoped ["https://www.googleapis.com/auth/userinfo.email"
                        "https://www.googleapis.com/auth/userinfo.profile"])
        .refreshAccessToken .getTokenValue
        once/authorization-header-with-bearer-token)))

(defn post
  "Post THING to Clio server with metadata MD."
  [thing md]
  (-> {:url (str/join "/" [(:clio deploy-environment) "api" "v1" "thing"])
       :method :post ;; :debug true :debug-body true
       :headers (merge {"Content-Type" "application/json"}
                       (get-authorization-header))
       :body (json/json-str md)}
      http/request :body
      (json/read-str :key-fn keyword)))

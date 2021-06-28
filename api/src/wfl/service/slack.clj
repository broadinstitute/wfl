(ns wfl.service.slack
  "Interact with Slack API."
  (:require [clojure.data.json :as json]
            [clojure.string :as str]
            [clj-http.client :as http]
            [wfl.environment :as env]
            [wfl.util :as util]))

(def api-url
  "https://slack.com/api/chat.postMessage")

(defn ^:private api-url
  "API URL for Slack."
  [& parts]
  (let [url "https://slack.com/api"]
    (str/join "/" (cons url parts))))

;; Slack Bot User token obtained from Vault
(defonce ^:private token
  (delay (:bot-user-token (#'env/vault-secrets "secret/dsde/gotc/dev/wfl/slack"))))

(defn ^:private header
  "Auth header for interacting with Slack API from `token`."
  [token]
  {:Authorization (str "Bearer " token)})

(defn ^:private valid-channel-id?
  [channel-id]
  (and (not (util/email-address? channel-id))
       (str/starts-with? channel-id "C")))

(defn post-message
  "Post Slack `message` to `channel-id`."
  [channel-id message]
  {:pre [(valid-channel-id? channel-id)]}
  (let [url (api-url "chat.postMessage")
        data {:channel channel-id
              :text message}]
    (http/post url {:headers      (header @token)
                    :content-type :application/json
                    :body         (json/write-str data)})))

(comment
  (post-message "C026PTM4XPA" "Hi! :crazysmiley:"))

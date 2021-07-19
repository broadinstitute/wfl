(ns wfl.service.slack
  "Interact with Slack API."
  (:require [clojure.data.json :as json]
            [clojure.string :as str]
            [clj-http.client :as http]
            [wfl.environment :as env]
            [wfl.log :as log]
            [wfl.util :as util]))

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
  ; FIXME: suppress warning `javax.mail.internet.AddressException: Missing final '@domain'`
  (and #(not (util/email-address? channel-id))
       (str/starts-with? channel-id "C")))

(defn ^:private slack-api-raise-for-status
  "Slack API has its own way of reporting
   statuses, so we need to parse the `body`
   to raise for status."
  [body]
  (let [response (-> body json/read-str)]
    (prn (get response "error"))
    (when-not (get response "ok")
      (throw (ex-info "failed to notify via Slack"
               {:error (get response "error")})))))

(defn post-message
  "Post Slack `message` to `channel-id`."
  [channel-id message]
  {:pre [(valid-channel-id? channel-id)]}
  (let [url (api-url "chat.postMessage")
        data {:channel channel-id
              :text message}]
    (-> (http/post url {:headers      (header @token)
                        :content-type :application/json
                        :body         (json/write-str data)})
        :body
        slack-api-raise-for-status)))

;; Create the agent queue and attach a watcher
(def notifier (agent []))
(add-watch notifier :watcher
  (fn [_key _ref _old-state new-state]
    (log/debug (format "the current notification queue is: %s" new-state))))

(defn add-notification
  [channel-id message]
  {:pre [(valid-channel-id? channel-id)]}
  (send notifier #(into % [[channel-id message]])))

(comment
  (add-notification "C026PTM4XPA" "Hi! :crazysmiley: 1"))

(defn ^:private send-notification
  [queue]
  (if (seq queue)
    (let [[channel msg] (first queue)]
      (post-message channel msg)
      (vec (rest queue)))
    queue))

(defn start-notification-loop
  "Return a future that listens at `agent` and
   sends out notifications."
  [agent]
  (future (while true
            (send-off agent send-notification)
            (util/sleep-seconds 1))))

(ns wfl.service.slack
  "Interact with Slack API."
  (:require [clojure.data.json  :as json]
            [clojure.string     :as str]
            [clj-http.client    :as http]
            [wfl.environment    :as env]
            [wfl.log            :as log]
            [wfl.util           :as util])
  (:import [clojure.lang PersistentQueue]))

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

(defn slack-channel-watcher? [s]
  (when-let [[tag value] (seq s)]
    (and (= "SlackChannel" tag) (valid-channel-id? value))))

(defn email-watcher? [s]
  (when-let [[tag value] (seq s)]
    (and (= "EmailAddress" tag) (util/email-address? value))))

(defn ^:private slack-api-raise-for-status
  "Slack API has its own way of reporting
   statuses, so we need to parse the `body`
   to raise for status."
  [body]
  (let [response (json/read-str body)]
    (when-not (get response "ok")
      (throw (ex-info "failed to notify via Slack"
                      {:error (get response "error")})))))

(defn post-message
  "Post Slack `message` to `channel-id`."
  [channel message]
  {:pre [(valid-channel-id? channel)]}
  (let [data {:channel channel
              :text    message}]
    (-> "https://slack.com/api/chat.postMessage"
      (http/post {:headers      (header @token)
                  :content-type :application/json
                  :body         (json/write-str data)})
      :body
      slack-api-raise-for-status)))

;; Create the agent queue and attach a watcher
;;
(def notifier (agent (PersistentQueue/EMPTY)))
(add-watch notifier :watcher
           (fn [_key _ref _old-state new-state]
             (log/debug (format "the current notification queue is: %s" (seq new-state)))))

(defn add-notification
  "Add notification defined by a map of
   `channel` and `message` to `agent` queue."
  [agent {:keys [channel message]}]
  {:pre [(valid-channel-id? channel)]}
  (send agent #(conj % {:channel channel :message message})))

(defn ^:private send-notification
  [queue]
  (if (seq queue)
    (let [{:keys [channel message]} (peek queue)]
      (post-message channel message)
      (pop queue))
    queue))

;; FIXME: add permission checks for slack-channel-watchers
;;
(defn notify-watchers
  "Notify `watchers` of an `exception` in the workload with `uuid`."
  [watchers uuid exception]
  (let [message        (format "Workload %s update threw %s" uuid (.getMessage exception))
        [_emails channels] (split-with email-watcher? watchers)]
    (letfn [(notify [[_tag channel]]
              (let [payload {:channel channel :message message}]
                (log/info payload)
                (add-notification notifier payload)))]
      (run! notify channels))))

(defn start-notification-loop
  "Return a future that listens at `agent` and
   sends out notifications."
  [agent]
  (future (while true
            (send-off agent send-notification)
            (util/sleep-seconds 1))))

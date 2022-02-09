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
  (delay (:bot-user-token
          (#'env/vault-secrets "secret/dsde/gotc/dev/wfl/slack"))))

(def enabled-env-var-name "WFL_SLACK_ENABLED")

;; FIXME: suppress warning `javax.mail.internet.AddressException: Missing final '@domain'`
;;
(defn ^:private valid-channel-id?
  [channel-id]
  (and (not (util/email-address? channel-id))
       (str/starts-with? channel-id "C")))

(defn slack-channel-watcher? [s]
  (when-let [[tag value & _] s]
    (and (= "slack" tag) (valid-channel-id? value))))

(defn email-watcher? [s]
  (when-let [[tag value & _] s]
    (and (= "email" tag) (util/email-address? value))))

;; https://api.slack.com/reference/surfaces/formatting#linking-urls
;;
(defn link
  "Return a mrkdwn link to `url` with `description."
  [url description]
  (format "<%s|%s>" url description))

(defn ^:private slack-api-raise-for-status
  "Slack API has its own way of reporting
   statuses, so we need to parse the `body`
   to raise for status."
  [body]
  (let [response (json/read-str body)]
    (when-not (response "ok")
      (throw (ex-info "failed to notify via Slack"
                      {:error (response "error")})))
    response))

(defn post-message
  "Post message to channel and pass response to callback."
  ([channel message]
   {:pre [(valid-channel-id? channel)]}
   (-> "https://slack.com/api/chat.postMessage"
       (http/post {:headers      {:Authorization (str "Bearer " @token)}
                   :content-type :application/json
                   :body         (json/write-str {:channel channel
                                                  :text    message})})
       :body
       slack-api-raise-for-status))
  ([channel message callback]
   (callback (post-message channel message))))

;; Create the agent queue and attach a watcher
;;
(def notifier (agent (PersistentQueue/EMPTY)))
(add-watch notifier :watcher
           (fn [_key _ref _old-state new-state]
             (when-let [queue (seq new-state)]
               (log/debug "The current notification queue is"
                          :queue queue))))

(defn add-notification
  "Add notification defined by a map of
   `channel` and `message` to `agent` queue."
  [agent {:keys [channel message]}]
  {:pre [(valid-channel-id? channel)]}
  (send agent #(conj % {:channel channel :message message})))

(defn send-notification
  [queue]
  (if (seq queue)
    (let [{:keys [channel message]} (peek queue)]
      (post-message channel message)
      (pop queue))
    queue))

;; FIXME: add permission checks for slack-channel-watchers
;;
(defn notify-watchers
  "Send `message` associated with workload `uuid` to Slack `watchers`."
  [{:keys [watchers uuid project labels] :as _workload} message]
  (let [feature-switch (env/getenv enabled-env-var-name)]
    (if (= "enabled" feature-switch)
      (let [channels (filter slack-channel-watcher? watchers)
            project  (or project (util/label-value labels "project"))
            swagger  (str/join "/" [(env/getenv "WFL_WFL_URL") "swagger"])
            header   (format "%s workload %s *%s*"
                             (link swagger "WFL") project uuid)]
        (letfn [(notify [[_tag channel-id _channel-name]]
                  (let [payload {:channel channel-id
                                 :message (str/join \newline [header message])}]
                    (log/info payload :labels labels :workload uuid)
                    (add-notification notifier payload)))]
          (run! notify channels)))
      (log/info {:slackDisabled
                 {:env-var-name       enabled-env-var-name
                  :env-var-val        feature-switch
                  :workload           uuid
                  :would-have-slacked (pr-str message)}}))))

(defn start-notification-loop
  "Return a future that listens at `agent` and
   sends out notifications."
  [agent]
  (future (while true
            (send-off agent send-notification)
            (util/sleep-seconds 1))))

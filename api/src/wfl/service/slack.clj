(ns wfl.service.slack
  "Interact with Slack API."
  (:require [clojure.data.json :as json]
            [clojure.string    :as str]
            [clj-http.client   :as http]
            [wfl.environment   :as env]
            [wfl.log           :as log]
            [wfl.util          :as util]
            [wfl.api.workloads :as workloads])
  (:import [clojure.lang PersistentQueue]))

(def enabled-env-var-name "WFL_SLACK_ENABLED")

(defn ^:private valid-channel-id?
  [channel-id]
  (str/starts-with? channel-id "C"))

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

;; More information on the meaning of error responses:
;; https://api.slack.com/methods/chat.postMessage#errors
;;
(defn ^:private post-message
  "Post `message` to `channel`."
  [channel message]
  (let [headers {:Authorization (str "Bearer " (env/getenv "WFL_SLACK_TOKEN"))}
        body    (json/write-str {:channel channel :text message})]
    (-> "https://slack.com/api/chat.postMessage"
        (http/post {:headers      headers
                    :content-type :application/json
                    :body         body})
        util/response-body-json)))

;; Slack API has its own way of reporting statuses:
;; https://api.slack.com/web#slack-web-api__evaluating-responses
;;
(defn ^:private post-message-or-throw
  "Post `message` to `channel` and throw if response indicates a failure."
  [channel message]
  {:pre [(valid-channel-id? channel)]}
  (let [response (post-message channel message)]
    (when-not (:ok response)
      (throw (ex-info "Slack API chat.postMessage failed"
                      {:channel  channel
                       :message  message
                       :response response})))
    response))

(def ^:private notifier (agent (PersistentQueue/EMPTY)))

(defn ^:private log-notifier-queue-content
  [_key _ref _old-state new-state]
  (when-let [queue (seq new-state)]
    (log/debug "Slack queue" :queue queue)))

(add-watch notifier log-notifier-queue-content log-notifier-queue-content)

(defn ^:private queue-notification
  "Add notification of `message` for `channel` to `notifier`."
  [{:keys [channel message] :as _notification}]
  {:pre [(valid-channel-id? channel)]}
  (send notifier conj {:channel channel :message message}))

(defn dispatch-notification
  "Dispatch the next notification in `queue`."
  [queue]
  (if-let [{:keys [channel message] :as notification} (peek queue)]
    (let [popped (pop queue)]
      (try
        (post-message-or-throw channel message)
        popped
        (catch Throwable throwable
          (log/error "post-message-or-throw threw"
                     :notification notification
                     :throwable    throwable)
          (conj popped notification))))
    queue))

;; FIXME: add permission checks for slack-channel-watchers
;;
(defn notify-watchers
  "Send `message` associated with workload `uuid` to Slack `watchers`."
  [{:keys [watchers uuid project labels] :as workload} message]
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
                    (log/info "About to Slack"
                              :workload (workloads/to-log workload)
                              :payload  (pr-str payload))
                    (queue-notification payload)))]
          (run! notify channels)))
      (log/info "Slack disabled"
                :workload           (workloads/to-log workload)
                :env-var-name       enabled-env-var-name
                :env-var-val        feature-switch
                :would-have-slacked (pr-str message)))))

(defn start-notification-loop
  "Return a future that listens at `agent` and
   sends out notifications."
  []
  (future
    (while true
      (try
        (send-off notifier dispatch-notification)
        (catch Throwable throwable
          (log/error "dispatch-notification threw" :throwable throwable)))
      (util/sleep-seconds 1))))

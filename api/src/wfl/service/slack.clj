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

;; Disabled logger suppresses warning:
;; `javax.mail.internet.AddressException: Missing final '@domain'`
;;
(defn ^:private valid-channel-id?
  [channel-id]
  (binding [wfl.log/*logger* wfl.log/disabled-logger]
    (and (not (util/email-address? channel-id))
         (str/starts-with? channel-id "C"))))

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
(defn ^:private post-message-and-throw-if-failure-impl
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

;; Deferring implementation to allow for response verification in tests.
;;
(defn ^:private post-message-and-throw-if-failure
  "Post `message` to `channel` and throw if response indicates a failure."
  [channel message]
  (post-message-and-throw-if-failure-impl channel message))

;; Create the agent queue and attach a watcher
;;
(def notifier (agent (PersistentQueue/EMPTY)))
(add-watch notifier :watcher
           (fn [_key _ref _old-state new-state]
             (when-let [queue (seq new-state)]
               (log/debug "Current notification queue" :queue queue))))

(defn add-notification
  "Add notification of `message` for `channel` to `agent` queue."
  [agent {:keys [channel message]}]
  {:pre [(valid-channel-id? channel)]}
  (send agent #(conj % {:channel channel :message message})))

(defn send-notification
  [queue]
  (if (seq queue)
    (let [{:keys [channel message]} (peek queue)]
      (try
        (post-message-and-throw-if-failure channel message)
        (pop queue)
        (catch Throwable t
          (log/error "Failed when posting Slack message"
                     :channel   channel
                     :message   message
                     :throwable t)
          queue)))
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
                    (add-notification notifier payload)))]
          (run! notify channels)))
      (log/info "Slack disabled"
                :workload           (workloads/to-log workload)
                :env-var-name       enabled-env-var-name
                :env-var-val        feature-switch
                :would-have-slacked (pr-str message)))))

(defn start-notification-loop
  "Return a future that listens at `agent` and
   sends out notifications."
  [agent]
  (future
    (while true
      (try
        (send-off agent send-notification)
        (catch Throwable t
          (log/error "Failed when dispatching to Slack agent"
                     :throwable t)))
      (util/sleep-seconds 1))))

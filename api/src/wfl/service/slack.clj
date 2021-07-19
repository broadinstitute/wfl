(ns wfl.service.slack
  "Interact with Slack API."
  (:require [clojure.data.json :as json]
            [clojure.string :as str]
            [clj-http.client :as http]
            [wfl.environment :as env]
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

(comment
  ;; Implementation 1 - not working yet!
  (defn queue
    ([] (clojure.lang.PersistentQueue/EMPTY))
    ([coll]
     (reduce conj clojure.lang.PersistentQueue/EMPTY coll)))

  (letfn [(send-notification-every-1-second [queue]
            (while (seq queue)
              (let [[channel msg] (first queue)]
                (util/sleep-seconds 1)
                (post-message channel msg))
              (recur (rest queue))))]
    (let [notifications [["C026PTM4XPA" "Hi! :crazysmiley: 1"]
                         ["C026PTM4XPA" "Hi! :crazysmiley: 2"]
                         ["C026PTM4XPA" "Hi! :crazysmiley: 3"]]
          a (agent (queue))]
      (send-off a send-notification-every-1-second)
      (send #(conj a ["C026PTM4XPA" "Hi! :crazysmiley: 4"]))))

  ;; Implementation 2
  (def notifier (agent []))

  ;; enqueue
  (let [notifications [["C026PTM4XPA" "Hi! :crazysmiley: 1"]
                       ["C026PTM4XPA" "Hi! :crazysmiley: 2"]
                       ["C026PTM4XPA" "Hi! :crazysmiley: 3"]]]
    (send notifier #(into % notifications)))

  ;; dequeue
  (letfn [(send-notification-every-1-second [queue]
            (when (seq queue)
              (let [[channel msg] (first queue)]
                (util/sleep-seconds 1)
                (post-message channel msg))
              (recur (rest queue))))]
    (send-off notifier send-notification-every-1-second))
  )

(ns zero.service.pubsub
  "Talk to Google Cloud Pub/Sub for some reason..."
  (:require [clojure.data.json :as json]
            [clojure.string    :as str]
            [clj-http.client   :as http]
            [clj-http.util     :as http-util]
            [zero.once         :as once])
  (:import [java.util Base64]))

(def api-url
  "The Google Cloud API URL."
  "https://pubsub.googleapis.com/v1/")

(def push-handler-prefix
  "A pushEndpoint must have this URI prefix."
  "/_ah/push-handlers/")

(def attributes
  "Illustrative message attributes."
  {:publisher "zero"})

(defn throw-on-invalid-pushEndpoint!
  "True when pushEndpoint has the right URI prefix."
  [pushEndpoint]
  (let [[_ _ah push-handlers _] (str/split push-handler-prefix #"/")
        [scheme nada domain ah handlers] (str/split pushEndpoint #"/" 5)]
    (when-not (and (= "https:" scheme)
                   (= "" nada)
                   (seq domain)
                   (= _ah ah)
                   (= push-handlers handlers))
      (throw (IllegalArgumentException.
              (format "Bad pushEndpoint URL: '%s'" pushEndpoint))))))

(defn topic-name
  "The name of TOPIC in PROJECT."
  [project topic]
  (str "projects/" project "/topics/"
       (http-util/url-encode topic)))

(defn topic-url
  "The API URL referring to TOPIC in PROJECT."
  [project topic]
  (str api-url (topic-name project topic)))

(defn subscription-name
  "The SUBSCRIPTION name in PROJECT."
  [project subscription]
  (str "projects/" project "/subscriptions/"
       (http-util/url-encode subscription)))

(defn subscription-url
  "The API URL referring to SUBSCRIPTION in PROJECT."
  [project subscription]
  (str api-url (subscription-name project subscription)))

(defn list-topics
  "The topics in PROJECT."
  [project]
  (-> {:method       :get
       :url          (topic-url project "")
       :content-type :application/json
       :headers      (once/get-auth-header)}
      http/request
      :body
      (json/read-str :key-fn keyword)
      :topics
      (or [])))

(defn make-topic
  "Make TOPIC in PROJECT."
  [project topic]
  (-> {:method       :put
       :url          (topic-url project topic)
       :content-type :application/json
       :headers      (once/get-auth-header)}
      http/request
      :body
      (json/read-str :key-fn keyword)))

(defn delete-topic
  "Throw or delete the TOPIC in PROJECT."
  [project topic]
  (letfn [(deleted-this-time? [response]
            (case (:status response)
              200 true
              404 false
              (-> (list 'delete-topic project topic)
                  pr-str
                  (ex-info response)
                  throw)))]
    (-> {:method            :delete
         :url               (topic-url project topic)
         :headers           (once/get-auth-header)
         :throw-exceptions? false}
        http/request
        deleted-this-time?)))

(defn list-subscriptions
  "The subscriptions in PROJECT."
  [project]
  (-> {:method       :get
       :url          (subscription-url project "")
       :content-type :application/json
       :headers      (once/get-auth-header)}
      http/request
      :body
      (json/read-str :key-fn keyword)))

(defn- subscribe-request
  "Wrap the arguments in a PUT request to subscribe."
  [project topic subscription]
  {:method       :put
   :url          (subscription-url project subscription)
   :content-type :application/json
   :headers      (once/get-auth-header)
   :body         {:topic (topic-name project topic)}})

(defn subscribe
  "Subscribe to TOPIC in PROJECT with the name SUBSCRIPTION.
  Push delivery to the pushEndpoint URL when specified."
  ([request]
   (letfn [(jsonify [edn] (json/write-str edn :escape-slash false))]
     (-> request
       (update-in [:body] jsonify)
       http/request :body
       (json/read-str :key-fn keyword))))
  ([request pushEndpoint]
   (subscribe (assoc-in request
                [:body :pushConfig]
                {:pushEndpoint pushEndpoint})))
  ([project topic subscription]
   (subscribe (subscribe-request project topic subscription)))
  ([project topic subscription pushEndpoint]
   (throw-on-invalid-pushEndpoint! pushEndpoint)
   (subscribe (subscribe-request project topic subscription) pushEndpoint)))

(defn unsubscribe
  "Unsubscribe to SUBSCRIPTION in PROJECT."
  [project subscription]
  (letfn [(deleted-this-time? [response]
            (case (:status response)
              200 true
              404 false
              (-> (list 'unsubscribe project subscription)
                  pr-str
                  (ex-info response)
                  throw)))]
    (-> {:method            :delete
         :url               (subscription-url project subscription)
         :headers           (once/get-auth-header)
         :throw-exceptions? false}
        http/request
        deleted-this-time?)))

;; OMG: Messages have to be base64-encoded strings.
;;
(defn publish
  "Publish the MESSAGES to TOPIC in PROJECT and return their messageIds."
  [project topic & messages]
  (letfn [(jsonify [edn] (json/write-str edn :escape-slash false))
          (encode [data] (->> data
                           jsonify
                           .getBytes
                           (.encodeToString (Base64/getEncoder))))
          (wrap [message] {:data       (encode message)
                           :attributes attributes})]
    (-> {:method  :post
         :url     (str api-url (topic-name project topic) :publish)
         :headers (once/get-auth-header)
         :body    (jsonify {:messages (map wrap messages)})}
      http/request :body
      (json/read-str :key-fn keyword)
      :messageIds)))

;; The :returnImmediately and :maxMessages are just illustrative.
;;
(defn pull
  "Return any MESSAGES from SUBSCRIPTION in PROJECT."
  [project subscription]
  (letfn [(jsonify [edn] (json/write-str edn :escape-slash false))
          (ednify [json] (json/read-str json :key-fn keyword))
          (decode [data] (->> data
                           (.decode (Base64/getDecoder))
                           String. ednify))
          (unwrap [m] (update-in m [:message :data] decode))]
    (-> {:method  :post
         :url     (str api-url (subscription-name project subscription) :pull)
         :headers (once/get-auth-header)
         :body    (jsonify {:returnImmediately true :maxMessages 23})}
      http/request :body
      (json/read-str :key-fn keyword)
      :receivedMessages
      (->> (map unwrap)))))

(defn acknowledge
  "Acknowledge messages from SUBSCRIPTION in PROJECT given their ACK-IDS"
  [project subscription ack-ids]
  (letfn [(jsonify [edn] (json/write-str edn :escape-slash false))]
    (-> {:method  :post
       :url     (str api-url (subscription-name project subscription) :acknowledge)
       :headers (once/get-auth-header)
       :body    (jsonify {:ackIds ack-ids})}
      http/request :body
      (json/read-str :key-fn keyword))))

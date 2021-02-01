(ns wfl.service.google.pubsub
  "Wrappers for Google Cloud Pub/Sub REST APIs.
  See https://cloud.google.com/pubsub/docs/reference/rest"
  (:require [clj-http.client :as http]
            [clojure.data.json :as json]
            [clojure.string :as str]
            [wfl.once :as once]
            [wfl.util :as util]))

(defn ^:private json-body [response]
  (-> response :body (or "null") util/parse-json))

(def ^:private pubsub-url
  (partial str "https://pubsub.googleapis.com/v1/"))

;; Google Cloud Pub/Sub Topics
;; https://cloud.google.com/pubsub/docs/reference/rest/v1/projects.topics

(defn create-topic [project topic-id]
  (-> (pubsub-url (str/join "/" ["projects" project "topics" topic-id]))
      (http/put {:headers (once/get-auth-header)})
      json-body
      :name))

(defn delete-topic [topic]
  (http/delete (pubsub-url topic) {:headers (once/get-auth-header)}))

(defn list-topics [project]
  (-> (pubsub-url (str/join "/" ["projects" project "topics"]))
      (http/get {:headers (once/get-auth-header)})
      json-body
      :topics))

(defn get-topic-iam-policy [topic]
  (-> (pubsub-url topic ":getIamPolicy")
      (http/get {:headers (once/get-auth-header)})
      json-body))

(defn set-topic-iam-policy [topic role->members]
  (letfn [(make-binding [[role members]] {:role role :members members})]
    (http/post
     (pubsub-url topic ":setIamPolicy")
     {:headers      (once/get-auth-header)
      :content-type :json
      :body         (json/write-str
                     {:policy {:bindings (map make-binding role->members)}}
                     :escape-slash false)})))

;; Google Cloud Pub/Sub Subscriptions
;; https://cloud.google.com/pubsub/docs/reference/rest/v1/projects.subscriptions

(defn acknowledge [subscription message-responses]
  (http/post
   (pubsub-url subscription ":acknowledge")
   {:headers (once/get-auth-header)
    :body    (json/write-str
              {:ackIds (map :ackId message-responses)}
              :escape-slash false)}))

(defn create-subscription [topic subscription-id]
  (let [project (second (str/split topic #"/"))]
    (-> (pubsub-url
         (str/join "/" ["projects" project "subscriptions" subscription-id]))
        (http/put
         {:headers      (once/get-auth-header)
          :content-type :json
          :body         (json/write-str {:topic topic} :escape-slash false)})
        json-body
        :name)))

(defn delete-subscription [subscription]
  (http/delete (pubsub-url subscription) {:headers (once/get-auth-header)}))

(defn list-subscriptions [project]
  (-> (pubsub-url project "/subscriptions")
      (http/get {:headers (once/get-auth-header)})
      json-body
      :subscriptions))

(defn list-subscriptions-for-topic [topic]
  (-> (pubsub-url topic "/subscriptions")
      (http/get {:headers (once/get-auth-header)})
      json-body
      :subscriptions))

(defn pull-subscription [subscription]
  (-> (pubsub-url subscription ":pull")
      (http/post {:headers (once/get-auth-header)
                  :body    (json/write-str
                            {:maxMessages 100}
                            :escape-slash false)})
      json-body
      :receivedMessages
      (or [])))

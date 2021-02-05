(ns wfl.service.google.pubsub
  "Wrappers for Google Cloud Pub/Sub REST APIs.
   See https://cloud.google.com/pubsub/docs/reference/rest"
  (:require [clj-http.client :as http]
            [clojure.data.json :as json]
            [clojure.string :as str]
            [wfl.once :as once]
            [wfl.util :refer [response-body-json]]))

(def ^:private pubsub-url
  (partial str "https://pubsub.googleapis.com/v1/"))

;; Google Cloud Pub/Sub Topics
;; https://cloud.google.com/pubsub/docs/reference/rest/v1/projects.topics

(defn create-topic
  "Create a Pub/Sub topic in `project` with the specified `topic-id`. Returns a
   newly created topic name in the form \"/projects/PROJECT/topics/TOPIC-ID\".

   Parameters
   ----------
   project  - Google Cloud Project to create the Pub/Sub topic in.
   topic-id - unique identifier for the topic

   Example
   -------
     (let [topic (create-topic \"broad-gotc-dev\" \"my-topic\")]
       #_(;; use topic ))"
  [project topic-id]
  (-> (pubsub-url (str/join "/" ["projects" project "topics" topic-id]))
      (http/put {:headers (once/get-auth-header)})
      response-body-json
      :name))

(defn delete-topic
  "Delete a Pub/Sub `topic`. See also `create-topic`.

   Parameters
   ----------
   topic - Google Cloud Pub/Sub topic in the form
           \"/projects/PROJECT/topics/TOPIC-ID\""
  [topic]
  (http/delete (pubsub-url topic) {:headers (once/get-auth-header)}))

(defn list-topics
  "List Pub/Sub topics in `project`. See also `create-topic`. Returns a list
   of topics.

   Parameters
   ----------
   topic - Google Cloud Pub/Sub topic in the form
           \"/projects/PROJECT/topics/TOPIC-ID\""
  [project]
  (-> (pubsub-url (str/join "/" ["projects" project "topics"]))
      (http/get {:headers (once/get-auth-header)})
      response-body-json
      :topics))

;; Google Cloud Pub/Sub Subscriptions
;; https://cloud.google.com/pubsub/docs/reference/rest/v1/projects.subscriptions

(defn acknowledge
  "Acknowledge messages pulled from server for `subscription`.
   `message-responses` should not be empty. See also `pull-messages`.

   Parameters
   ----------
   subscription      - Google Cloud Pub/Sub subscription in the form
                       \"/projects/PROJECT/subscriptions/SUBSCRIPTION-ID\"
   message-responses - Server responses from `pull-messages`"
  [subscription message-responses]
  (http/post
   (pubsub-url subscription ":acknowledge")
   {:headers (once/get-auth-header)
    :body    (json/write-str
              {:ackIds (map :ackId message-responses)}
              :escape-slash false)}))

(defn create-subscription
  "Create a Pub/Sub subscription to Pub/Sub `topic` with the specified
   `subscription-id`. Returns a newly created subscription name in the form
   \"/projects/PROJECT/subscriptions/SUBSCRIPTION-ID\".

   Parameters
   ----------
   topic           - Google Cloud Pub/Sub topic in the form
                     \"/projects/PROJECT/topics/TOPIC-ID\"\"
   subscription-id - unique identifier for the subscription

   Example
   -------
     (let [subscription (create-subscription
                          \"/projects/broad-gotc-dev/topics/my-topic\"
                          \"my-subscription\")]
       #_(;; use subscription ))"
  [topic subscription-id]
  (let [project (second (str/split topic #"/"))]
    (-> (pubsub-url
         (str/join "/" ["projects" project "subscriptions" subscription-id]))
        (http/put
         {:headers      (once/get-auth-header)
          :content-type :json
          :body         (json/write-str {:topic topic} :escape-slash false)})
        response-body-json
        :name)))

(defn delete-subscription
  "Delete a Pub/Sub `subscription``. See also `create-subscription`.

   Parameters
   ----------
   subscription - Google Cloud Pub/Sub subscription in the form
                  \"/projects/PROJECT/subscriptions/SUBSCRIPTION-ID\""
  [subscription]
  (http/delete (pubsub-url subscription) {:headers (once/get-auth-header)}))

(defn list-subscriptions
  "List active Pub/Sub subscriptions for the specified `project` or `topic`.
   See also `create-topic`, `create-subscription`. Returns a list of
   subscriptions.

   Parameters
   ----------
   project-or-topic - Google Cloud Project or Pub/Sub topic in the form
                      \"/projects/PROJECT/topics/TOPIC-ID\""
  [project-or-topic]
  (-> (pubsub-url project-or-topic "/subscriptions")
      (http/get {:headers (once/get-auth-header)})
      response-body-json
      :subscriptions))

(defn pull-subscription
  "Pull messages for `subscription`. Note that the server may return UNAVAILABLE
   if there are too many concurrent pull requests pending for the given
   `subscription`.

   Parameters
   ----------
   subscription - Google Cloud Pub/Sub subscription in the form
                  \"/projects/PROJECT/subscriptions/SUBSCRIPTION-ID\""
  [subscription]
  (-> (pubsub-url subscription ":pull")
      (http/post {:headers (once/get-auth-header)
                  :body    (json/write-str
                            {:maxMessages 100}
                            :escape-slash false)})
      response-body-json
      :receivedMessages
      (or [])))

;; IAM
(defn get-iam-policy
  "Get the IAM policy for the given Pub/Sub `resource`.

   Parameters
   ----------
   resource - topic, subscription or snapshot. See the Google Cloud Pub/Sub
              reference for more information."
  [resource]
  (-> (pubsub-url resource ":getIamPolicy")
      (http/get {:headers (once/get-auth-header)})
      response-body-json))

(defn set-iam-policy
  "Set the IAM policy for the given Pub/Sub `resource` with the specified
   role->member bindings. A Pub/Sub `resource` is a topic, a subscription or a
   snapshot.

   Parameters
   ----------
   resource      - topic, subscription or snapshot. See the Google Cloud Pub/Sub
                   reference for more information.
   role->members - map from a Google Cloud Pub/Sub role to a list of members.
                   See https://cloud.google.com/pubsub/docs/access-control#permissions_and_roles"
  [resource role->members]
  (letfn [(make-binding [[role members]] {:role role :members members})]
    (http/post
     (pubsub-url resource ":setIamPolicy")
     {:headers      (once/get-auth-header)
      :content-type :json
      :body         (json/write-str
                     {:policy {:bindings (map make-binding role->members)}}
                     :escape-slash false)})))

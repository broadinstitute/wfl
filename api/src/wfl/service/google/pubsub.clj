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
  (-> (pubsub-url (str/join "/" ["projects" project "topics" topic-id]))
      (http/put {:headers (once/get-auth-header)})
      json-body
      :name))

(defn delete-topic [topic]
  "Delete a Pub/Sub `topic`. See also `create-topic`.

   Parameters
   ----------
   topic - Google Cloud Pub/Sub topic in the form
           \"/projects/PROJECT/topics/TOPIC-ID\""
  (http/delete (pubsub-url topic) {:headers (once/get-auth-header)}))

(defn list-topics [project]
  "List Pub/Sub topics in `project`. See also `create-topic`. Returns a list
   of topics.

   Parameters
   ----------
   topic - Google Cloud Pub/Sub topic in the form
           \"/projects/PROJECT/topics/TOPIC-ID\""
  (-> (pubsub-url (str/join "/" ["projects" project "topics"]))
      (http/get {:headers (once/get-auth-header)})
      json-body
      :topics))

;; Google Cloud Pub/Sub Subscriptions
;; https://cloud.google.com/pubsub/docs/reference/rest/v1/projects.subscriptions

(defn acknowledge [subscription message-responses]
  "Acknowledge messages pulled from server for `subscription`.
   `message-responses` should not be empty. See also `pull-messages`.

   Parameters
   ----------
   subscription      - Google Cloud Pub/Sub subscription in the form
                       \"/projects/PROJECT/subscriptions/SUBSCRIPTION-ID\"
   message-responses - Server responses from `pull-messages`"
  (http/post
   (pubsub-url subscription ":acknowledge")
   {:headers (once/get-auth-header)
    :body    (json/write-str
              {:ackIds (map :ackId message-responses)}
              :escape-slash false)}))

(defn create-subscription [topic subscription-id]
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
  "Delete a Pub/Sub `subscription``. See also `create-subscription`.

   Parameters
   ----------
   subscription - Google Cloud Pub/Sub subscription in the form
                  \"/projects/PROJECT/subscriptions/SUBSCRIPTION-ID\""
  (http/delete (pubsub-url subscription) {:headers (once/get-auth-header)}))

(defn list-subscriptions [project-or-topic]
  "List active Pub/Sub subscriptions for the specified `project` or `topic`.
   See also `create-topic`, `create-subscription`. Returns a list of
   subscriptions.

   Parameters
   ----------
   project-or-topic - Google Cloud Project or Pub/Sub topic in the form
                      \"/projects/PROJECT/topics/TOPIC-ID\""
  (-> (pubsub-url project-or-topic "/subscriptions")
      (http/get {:headers (once/get-auth-header)})
      json-body
      :subscriptions))

(defn pull-subscription [subscription]
  "Pull messages for `subscription`. Note that the server may return UNAVAILABLE
   if there are too many concurrent pull requests pending for the given
   `subscription`.

   Parameters
   ----------
   subscription - Google Cloud Pub/Sub subscription in the form
                  \"/projects/PROJECT/subscriptions/SUBSCRIPTION-ID\""
  (-> (pubsub-url subscription ":pull")
      (http/post {:headers (once/get-auth-header)
                  :body    (json/write-str
                            {:maxMessages 100}
                            :escape-slash false)})
      json-body
      :receivedMessages
      (or [])))

;; IAM
(defn get-iam-policy [resource]
  "Get the IAM policy for the given Pub/Sub `resource`.

   Parameters
   ----------
   resource - topic, subscription or snapshot. See the Google Cloud Pub/Sub
              reference for more information."
  (-> (pubsub-url resource ":getIamPolicy")
    (http/get {:headers (once/get-auth-header)})
    json-body))

(defn set-iam-policy [resource role->members]
  "Set the IAM policy for the given Pub/Sub `resource` with the specified
   role->member bindings. A Pub/Sub `resource` is a topic, a subscription or a
   snapshot.

   Parameters
   ----------
   resource      - topic, subscription or snapshot. See the Google Cloud Pub/Sub
                   reference for more information.
   role->members - map from a Google Cloud Pub/Sub role to a list of members.
                   See https://cloud.google.com/pubsub/docs/access-control#permissions_and_roles"
  (letfn [(make-binding [[role members]] {:role role :members members})]
    (http/post
      (pubsub-url resource ":setIamPolicy")
      {:headers      (once/get-auth-header)
       :content-type :json
       :body         (json/write-str
                       {:policy {:bindings (map make-binding role->members)}}
                       :escape-slash false)})))

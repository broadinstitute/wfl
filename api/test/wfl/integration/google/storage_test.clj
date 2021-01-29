(ns wfl.integration.google.storage-test
  (:require [clj-http.client :as client]
            [clojure.data.json :as json]
            [clojure.string :as str]
            [clojure.test :refer [deftest is]]
            [wfl.once :as once]
            [wfl.service.gcs :as gcs]
            [wfl.util :as util]
            [wfl.tools.fixtures :refer [with-temporary-gcs-folder]]
            [wfl.tools.fixtures :as fixtures])
  (:import (java.util UUID)))

;; helper functions
(defn ^:private wget [url]
  (-> (client/get url {:headers (once/get-auth-header)}) :body util/parse-json))

(def ^:private pubsub-url
  (partial str "https://pubsub.googleapis.com/v1/"))

;; pub/sub topics
(defn ^:private list-topics [project]
  (-> (pubsub-url (str/join "/" ["projects" project "topics"]))
      wget
      :topics
      (or [])))

(defn ^:private create-topic [project topic-id]
  (-> (pubsub-url (str/join "/" ["projects" project "topics" topic-id]))
      (client/put {:headers (once/get-auth-header)})
      :body
      util/parse-json
      :name))

(defn ^:private delete-topic [topic]
  (client/delete
   (pubsub-url topic)
   {:headers (once/get-auth-header)}))

(defn ^:private get-topic-iam-policy [topic]
  (wget (pubsub-url topic ":getIamPolicy")))

(defn ^:private set-topic-iam-policy [topic role->members]
  (letfn [(make-binding [[role members]] {:role role :members members})]
    (client/post
     (pubsub-url topic ":setIamPolicy")
     {:headers      (once/get-auth-header)
      :content-type :json
      :body         (json/write-str
                     {:policy {:bindings (map make-binding role->members)}}
                     :escape-slash false)})))

;; pub/sub subscriptions
(defn ^:private list-subscriptions [project]
  (-> (pubsub-url project "/subscriptions")
      wget
      :subscriptions
      (or [])))

(defn ^:private list-subscriptions-for-topic [topic]
  (-> (pubsub-url topic "/subscriptions")
      wget
      :subscriptions
      (or [])))

(defn ^:private create-subscription [topic subscription-id]
  (let [project (second (str/split topic #"/"))]
    (-> (pubsub-url
         (str/join "/" ["projects" project "subscriptions" subscription-id]))
        (client/put
         {:headers      (once/get-auth-header)
          :content-type :json
          :body         (json/write-str {:topic topic} :escape-slash false)})
        :body
        util/parse-json
        :name)))

(defn ^:private delete-subscription [subscription]
  (client/delete
   (pubsub-url subscription)
   {:headers (once/get-auth-header)}))

(defn ^:private pull-messages [subscription & opts]
  (-> (pubsub-url subscription ":pull")
      (client/post {:headers (once/get-auth-header)
                    :body    (json/write-str
                              {:returnImmediately (util/absent? (set opts) :block)
                               :maxMessages       100}
                              :escape-slash false)})
      :body
      util/parse-json
      :receivedMessages
      (or [])))

(defn ^:private acknowledge [subscription message-responses]
  (-> (pubsub-url subscription ":acknowledge")
      (client/post {:headers (once/get-auth-header)
                    :body    (json/write-str
                              {:ackIds (map :ackId message-responses)}
                              :escape-slash false)})))

;; storage pub/sub notification configuration
(defn ^:private create-notification-configuration [bucket topic]
  (let [payload {:payload_format "JSON_API_V1"
                 :event_types    ["OBJECT_FINALIZE"]
                 :topic          topic}]
    (-> (str gcs/bucket-url bucket "/notificationConfigs")
        (client/post
         {:headers      (once/get-auth-header)
          :content-type :json
          :body         (json/write-str payload :escape-slash false)})
        :body
        util/parse-json)))

(defn ^:private list-notification-configurations [bucket]
  (-> (str gcs/bucket-url bucket "/notificationConfigs")
      (client/get {:headers (once/get-auth-header)})
      :body
      util/parse-json
      :items))

(defn ^:private delete-notification-configuration [bucket {:keys [id]}]
  (client/delete
   (str gcs/bucket-url bucket "/notificationConfigs/" id)
   {:headers (once/get-auth-header)}))

(defn ^:private get-cloud-storage-service-account [project]
  (-> (str gcs/storage-url (str/join "/" ["projects" project "serviceAccount"]))
      (client/get {:headers (once/get-auth-header)})
      :body
      util/parse-json))

(defn ^:private give-project-publish-access-to-topic [project topic]
  (let [sa (-> project get-cloud-storage-service-account :email_address)]
    (set-topic-iam-policy
     topic
     {"roles/pubsub.publisher" [(str "serviceAccount:" sa)]
      "roles/pubsub.editor"    [(str "serviceAccount:"
                                     (once/service-account-email))]})))

;; testing helpers
(defn ^:private bracket [acquire release use]
  "See https://wiki.haskell.org/Bracket_pattern"
  (let [resource (acquire)]
    (try
      (use resource)
      (finally
        (release resource)))))

(defn with-temporary-topic [project f]
  "Create a temporary Google Cloud Storage Pub/Sub topic"
  (bracket
   #(create-topic project (str "wfl-test-" (UUID/randomUUID)))
   delete-topic
   f))

(defn with-temporary-notification-configuration [bucket topic f]
  "Create a temporary Google Cloud Storage Pub/Sub notification configuration"
  (bracket
   #(create-notification-configuration bucket topic)
   #(delete-notification-configuration bucket %)
   f))

(defn with-temporary-subscription [topic f]
  "Create a temporary Google Cloud Storage Pub/Sub subscription"
  (bracket
   #(create-subscription topic
                         (str "wfl-test-subscription-" (UUID/randomUUID)))
   delete-subscription
   f))

;; the test
(deftest test-cloud-storage-pubsub
  (let [project "broad-gotc-dev-storage"
        bucket  fixtures/gcs-test-bucket]
    (with-temporary-topic project
      (fn [topic]
        (give-project-publish-access-to-topic project topic)
        (with-temporary-notification-configuration bucket topic
          (fn [_]
            (let [configs (list-notification-configurations bucket)]
              (is (< 0 (count configs))))
            (with-temporary-subscription topic
              (fn [subscription]
                (is (== 1 (count (list-subscriptions-for-topic topic))))
                (is (empty? (pull-messages subscription)))
                (with-temporary-gcs-folder uri
                  (let [dest (str uri "input.txt")]
                    (gcs/upload-file
                     (str/join "/" ["test" "resources" "copy-me.txt"])
                     dest)
                    (let [messages (pull-messages subscription :block)]
                      (is (== 1 (count messages)))
                      (acknowledge subscription messages))))))))))))

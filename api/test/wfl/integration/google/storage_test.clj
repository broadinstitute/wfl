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

(def ^:private pubsub-url "https://pubsub.googleapis.com/v1/")

;; pub/sub topics
(defn ^:private topic-url [topic-name]
  (str pubsub-url topic-name))

(defn ^:private list-pubsub-topics [project]
  (-> (str pubsub-url (str/join "/" ["projects" project "topics"]))
    wget
    :topics
    (or [])))

(defn ^:private create-pubsub-topic [project topic-id]
  (-> (topic-url (str/join "/" ["projects" project "topics" topic-id]))
    (client/put {:headers (once/get-auth-header)})
    :body
    util/parse-json
    :name))

(defn ^:private delete-pubsub-topic [topic]
  (client/delete
    (topic-url topic)
    {:headers (once/get-auth-header)}))

(defn ^:private get-topic-iam-policy [topic]
  (wget (str (topic-url topic) ":getIamPolicy")))

(defn ^:private set-topic-iam-policy [topic role->members]
  (letfn [(make-binding [[role members]] {:role role :members members})]
    (client/post
      (str (topic-url topic) ":setIamPolicy")
      {:headers      (once/get-auth-header)
       :content-type :json
       :body         (json/write-str
                       {:policy {:bindings (map make-binding role->members)}}
                       :escape-slash false)})))

;; pub/sub subscriptions

(defn ^:private list-subscriptions [project]
  (-> (str pubsub-url (str/join "/" [project "subscriptions"]))
    wget
    :subscriptions
    (or [])))

(defn ^:private list-topic-subscriptions [topic]
  (-> (str pubsub-url (str/join "/" [topic "subscriptions"]))
    wget
    :subscriptions
    (or [])))

(defn ^:private create-subscription [topic subscription-id]
  (let [project           (second (str/split topic #"/"))
        subscription-name (str/join "/" ["projects" project "subscriptions" subscription-id])]
    (-> (str pubsub-url subscription-name)
      (client/put
        {:headers      (once/get-auth-header)
         :content-type :json
         :body         (json/write-str {:topic topic} :escape-slash false)})
      :body
      util/parse-json
      :name)))

(defn ^:private delete-subscription [subscription]
  (client/delete
    (str pubsub-url subscription)
    {:headers (once/get-auth-header)}))

(defn ^:private pull-messages [subscription & opts]
  (-> (str pubsub-url subscription ":pull")
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
  (-> (str pubsub-url subscription ":acknowledge")
    (client/post {:headers (once/get-auth-header)
                  :body    (json/write-str
                             {:ackIds (map :ackId message-responses)}
                             :escape-slash false)})))

;; storage notification configuration

(defn ^:private create-pubsub-configuration [bucket topic]
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

(defn ^:private list-pubsub-configurations [bucket]
  (-> (str gcs/bucket-url bucket "/notificationConfigs")
    (client/get {:headers (once/get-auth-header)})
    :body
    util/parse-json
    :items))

(defn ^:private delete-pubsub-configuration [bucket {:keys [id]}]
  (client/delete
    (str gcs/bucket-url bucket "/notificationConfigs/" id)
    {:headers (once/get-auth-header)}))

(defn ^:private get-cloud-storage-service-account [project]
  (-> (str gcs/storage-url (str/join "/" ["projects" project "serviceAccount"]))
    (client/get {:headers (once/get-auth-header)})
    :body
    util/parse-json))

(defn ^:private configure-topic-iam-policy [project topic]
  (let [sa (-> project get-cloud-storage-service-account :email_address)]
    (set-topic-iam-policy
      topic
      {"roles/pubsub.publisher" [(str "serviceAccount:" sa)]
       "roles/pubsub.editor"    [(str "serviceAccount:" (once/service-account-email))]})))


;; testing helpers
(defn with-temporary-pubsub-topic [project f]
  "Create a temporary Google Cloud Storage Pub/Sub topic"
  (let [topic (create-pubsub-topic project (str "wfl-test-" (UUID/randomUUID)))]
    (try
      (configure-topic-iam-policy project topic)
      (f topic)
      (finally
        (delete-pubsub-topic topic)))))

(defn with-temporary-pubsub-configuration [bucket topic f]
  "Create a temporary Google Cloud Storage Pub/Sub notification configuration"
  (let [config (create-pubsub-configuration bucket topic)]
    (try
      (f config)
      (finally
        (delete-pubsub-configuration bucket config)))))

(defn with-temporary-pubsub-subscription [topic f]
  "Create a temporary Google Cloud Storage Pub/Sub subscription"
  (let [subscription (create-subscription topic (str "wfl-test-subscription-" (UUID/randomUUID)))]
    (try
      (f subscription)
      (finally
        (delete-subscription subscription)))))

;; the test
(deftest test-cloud-storage-pubsub
  (with-temporary-pubsub-topic "broad-gotc-dev-storage"
    (fn [topic]
      (let [bucket fixtures/gcs-test-bucket]
        (with-temporary-pubsub-configuration bucket topic
          (fn [_]
            (let [configs (list-pubsub-configurations bucket)]
              (is (< 0 (count configs))))
            (with-temporary-pubsub-subscription topic
              (fn [subscription]
                (is (== 1 (count (list-topic-subscriptions topic))))
                (is (empty? (pull-messages subscription)))
                (with-temporary-gcs-folder uri
                  (let [dest (str uri "input.txt")]
                    (gcs/upload-file
                      (str/join "/" ["test" "resources" "copy-me.txt"])
                      dest)
                    (let [messages (pull-messages subscription :block)]
                      (is (== 1 (count messages)))
                      (acknowledge subscription messages))))))))))))

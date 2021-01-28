(ns wfl.integration.google.storage-test
  (:require [clj-http.client :as client]
            [clojure.test :refer [deftest is]]
            [wfl.once :as once]
            [wfl.service.gcs :as gcs]
            [wfl.util :as util]
            [clojure.string :as str]
            [clojure.data.json :as json])
  (:import (java.util UUID)))

(def ^:private pubsub-url "https://pubsub.googleapis.com/v1/")

(defn ^:private topic-name [project topic-id]
  (str/join "/" ["projects" project "topics" topic-id]))

(defn ^:private topic-url [topic-name]
  (str pubsub-url topic-name))

(defn ^:private list-pubsub-topics [project]
  (-> (str pubsub-url (str/join "/" ["projects" project "topics"]))
    (client/get {:headers (once/get-auth-header)})
    :body
    util/parse-json
    :topics))

(defn ^:private create-pubsub-topic [topic-name]
  (client/put (topic-url topic-name)
    {:headers (once/get-auth-header)}))

(defn ^:private delete-pubsub-topic [topic-name]
  (client/delete (topic-url topic-name)
    {:headers (once/get-auth-header)}))

(defn ^:private get-topic-iam-policy [topic-name]
  (-> (str (topic-url topic-name) ":getIamPolicy")
    (client/get {:headers (once/get-auth-header)})
    :body
    util/parse-json))

(defn ^:private set-topic-iam-policy [topic-name role->members]
  (letfn [(make-binding [[role members]] {:role role :members members})]
    (client/post (str (topic-url topic-name) ":setIamPolicy")
      {:headers      (once/get-auth-header)
       :content-type :json
       :body         (json/write-str
                       {:policy {:bindings (map make-binding role->members)}}
                       :escape-slash false)})))

(defn ^:private insert-pubsub-configuration [bucket topic]
  (let [payload {:payload_format "JSON_API_V1"
                 :event_types    ["OBJECT_FINALIZE"]
                 :topic          topic}]
    (-> (str gcs/bucket-url bucket "/notificationConfigs")
      (client/post {:headers      (once/get-auth-header)
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
  (-> (str gcs/bucket-url bucket "/notificationConfigs/" id)
    (client/delete {:headers (once/get-auth-header)})
    :body))

(defn ^:private get-cloud-storage-service-account [project]
  (-> (str gcs/storage-url
        (str/join "/" ["projects" project "serviceAccount"]))
    (client/get {:headers (once/get-auth-header)})
    :body
    util/parse-json))

(defn ^:private configure-topic-iam-policy [project topic]
  (let [sa (-> project get-cloud-storage-service-account :email_address)]
    (set-topic-iam-policy topic
      {"roles/pubsub.publisher" [(str "serviceAccount:" sa)]
       "roles/pubsub.editor"    [(str "serviceAccount:" (once/service-account-email))]})))

(defn with-temporary-pubsub-topic [project f]
  "Create a temporary Google Cloud Storage Pub/Sub topic"
  (let [topic (topic-name project (str "wfl-test-" (UUID/randomUUID)))]
    (create-pubsub-topic topic)
    (try
      (configure-topic-iam-policy project topic)
      (f topic)
      (finally
        (delete-pubsub-topic topic)))))

(defn with-temporary-pubsub-configuration [bucket topic f]
  "Create a temporary Google Cloud Storage Pub/Sub notification configuration"
  (let [config (insert-pubsub-configuration bucket topic)]
    (try
      (f config)
      (finally
        (delete-pubsub-configuration bucket config)))))

(deftest test-list-cloud-storage-configurations
  (with-temporary-pubsub-topic "broad-gotc-dev"
    (fn [topic]
      (let [bucket "broad-gotc-dev-zero-test"]
        (with-temporary-pubsub-configuration bucket topic
          (fn [_]
            (let [configs (list-pubsub-configurations bucket)]
              (is (< 0 (count configs))))))))))

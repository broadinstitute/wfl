(ns zero.unit.pubsub-test
  "Test the Google Cloud Storage namespace."
  (:require [clojure.java.io     :as io]
            [clojure.string      :as str]
            [clojure.test        :refer [deftest is testing]]
            [zero.service.pubsub :as pubsub]
            [zero.service.gcs    :as gcs])
  (:import [java.util UUID]))

(def project
  "Test in this Google Cloud project."
  "broad-gotc-dev")

(def uuid
  "A new random UUID string."
  (str (UUID/randomUUID)))

(def prefix
  "A unique prefix for naming things here."
  (str/join "-" [(System/getenv "USER") "test" uuid ""]))

(def topic        (str prefix "topic"))
(def subscription (str prefix "subscription"))

(deftest topic-test
  (testing "Topics"
    (testing "make"
      (let [expect (pubsub/topic-name project topic)]
        (is (= expect (:name (pubsub/make-topic project topic))))))
    (testing "list"
      (is (some #{(pubsub/topic-name project topic)}
                (map :name (pubsub/list-topics project)))))
    (testing "delete"
      (is (true?  (pubsub/delete-topic project topic)))
      (is (false? (pubsub/delete-topic project topic)))
      (is (false? (pubsub/delete-topic project topic))))))

(deftest subscription-test
  (try
    (pubsub/make-topic project topic)
    (testing "Subscriptions"
      (testing "subscribe"
        (is (= [(pubsub/subscription-name project subscription)
                (pubsub/topic-name project topic)]
               ((juxt :name :topic)
                (pubsub/subscribe project topic subscription)))))
      (testing "list"
        (is (some #{[(pubsub/subscription-name project subscription)
                     (pubsub/topic-name project topic)]}
                  (map (juxt :name :topic)
                       (:subscriptions
                        (pubsub/list-subscriptions project))))))
      (testing "unsubscribe"
        (is (true?  (pubsub/unsubscribe project subscription)))
        (is (false? (pubsub/unsubscribe project subscription)))
        (is (false? (pubsub/unsubscribe project subscription)))))
    (finally (pubsub/delete-topic project topic))))

(deftest message-test
  (try
    (pubsub/make-topic project topic)
    (pubsub/subscribe project topic subscription)
    (testing "Messages"
      (let [messages [:wut  23 :k  "v" [:wut  23 "x"] {:wut 23 :k "v"}]
            expected ["wut" 23 "k" "v" ["wut" 23 "x"] {:wut 23 :k "v"}]
            ids (apply pubsub/publish project topic messages)
            expect (map (fn [id data] {:data       data
                                       :messageId  id
                                       :attributes pubsub/attributes})
                        ids expected)]
        (testing "publish"
          (is (= (count messages) (count ids))))
        (testing "pull"
          (let [result (pubsub/pull project subscription)]
            (is (= expect
                   (map (fn [m] (select-keys m (keys (first expect))))
                        (map :message result))))))))
    (finally
      (pubsub/unsubscribe project subscription)
      (pubsub/delete-topic project topic))))

(def notification-bucket
  "A GCS bucket that publishes to a PubSub topic on file upload events."
  "test-storage-notifications")

(def notification-subscription
  "The subscription to the PubSub topic that the notification-bucket publishes to."
  "test-storage-notification-subscription")

(deftest bucket-notification-test
  (let [test-file (str "wfl-test-" (UUID/randomUUID) "/test.txt")]
    (try
      (spit "test.txt" "testing")
      (gcs/upload-file "./test.txt" notification-bucket test-file)
      (testing "pull"
        (let [result (pubsub/pull project notification-subscription)
              data (get-in (first result) [:message :data])]
          (is (= (:name data) test-file))
          (is (= (:bucket data) notification-bucket))
          (testing "acknowledge"
            (let [ack-ids (map :ackId result)
                  ack-result (pubsub/acknowledge project notification-subscription ack-ids)
                  pull-result (pubsub/pull project notification-subscription)]
            (is (= ack-result {}))
            (is (= (count pull-result) 0))))))
      (finally
        (gcs/delete-object notification-bucket test-file)
        (io/delete-file "test.txt")))))

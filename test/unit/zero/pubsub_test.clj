(ns zero.pubsub-test
  "Test the Google Cloud Storage namespace."
  (:require [clojure.string      :as str]
            [clojure.test        :refer [deftest is testing]]
            [zero.service.pubsub :as pubsub])
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

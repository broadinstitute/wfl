(ns wfl.integration.google.storage-test
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is]]
            [wfl.once :as once]
            [wfl.service.gcs :as storage]
            [wfl.service.google.pubsub :as pubsub]
            [wfl.tools.fixtures :as fixtures]))

(defn ^:private give-project-publish-access-to-topic [project topic]
  (let [sa (-> project storage/get-cloud-storage-service-account :email_address)]
    (pubsub/set-topic-iam-policy
     topic
     {"roles/pubsub.publisher" [(str "serviceAccount:" sa)]
      "roles/pubsub.editor"    [(str "serviceAccount:"
                                     (once/service-account-email))]})))

;; the test
(deftest test-cloud-storage-pubsub
  (let [project "broad-gotc-dev-storage"
        bucket  fixtures/gcs-test-bucket]
    (fixtures/with-temporary-topic project
      (fn [topic]
        (give-project-publish-access-to-topic project topic)
        (fixtures/with-temporary-notification-configuration bucket topic
          (fn [_]
            (let [configs (storage/list-notification-configurations bucket)]
              (is (< 0 (count configs))))
            (fixtures/with-temporary-subscription topic
              (fn [subscription]
                (is (== 1 (count (pubsub/list-subscriptions-for-topic topic))))
                (is (empty? (pubsub/pull-subscription subscription)))
                (fixtures/with-temporary-cloud-storage-folder bucket
                  (fn [url]
                    (let [dest (str url "input.txt")]
                      (storage/upload-file
                       (str/join "/" ["test" "resources" "copy-me.txt"])
                       dest)
                      (let [messages (pubsub/pull-subscription subscription)]
                        (is (< 0 (count messages)))
                        (pubsub/acknowledge subscription messages)))))))))))))

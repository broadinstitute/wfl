(ns wfl.integration.google.pubsub-test
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is]]
            [wfl.auth :as auth]
            [wfl.service.google.pubsub :as pubsub]
            [wfl.service.google.storage :as gcs]
            [wfl.tools.fixtures :as fixtures]))

(defn ^:private give-project-sa-publish-access-to-topic [project topic]
  (let [sa (-> project gcs/get-cloud-storage-service-account :email_address)]
    (pubsub/set-iam-policy
     topic
     {"roles/pubsub.publisher" [(str "serviceAccount:" sa)]
      "roles/pubsub.editor"    [(str "serviceAccount:"
                                     (auth/service-account-email))]})))

(deftest test-cloud-storage-pubsub
  (let [project "broad-gotc-dev-storage"
        bucket  fixtures/gcs-test-bucket]
    (fixtures/with-temporary-topic project
      (fn [topic]
        ;; We need to make sure that the service account of the project that
        ;; owns the storage bucket has the required permissions to publish
        ;; messages to the pub/sub topic.
        ;; See https://cloud.google.com/storage/docs/reporting-changes#prereqs
        (give-project-sa-publish-access-to-topic project topic)
        (fixtures/with-temporary-subscription topic
          (fn [subscription]
            (is (== 1 (count (pubsub/list-subscriptions topic))))
            (is (empty? (pubsub/pull-subscription subscription)))
            (fixtures/with-temporary-notification-configuration bucket topic
              (fn [_]
                (let [configs (gcs/list-notification-configurations bucket)]
                  (is (< 0 (count configs))))
                (fixtures/with-temporary-cloud-storage-folder bucket
                  (fn [url]
                    (let [dest (str url "input.txt")]
                      (gcs/upload-file
                       (str/join "/" ["test" "resources" "copy-me.txt"])
                       dest)
                      (let [messages (pubsub/pull-subscription subscription)]
                        (is (< 0 (count messages)))
                        (pubsub/acknowledge subscription messages)))))))))))))

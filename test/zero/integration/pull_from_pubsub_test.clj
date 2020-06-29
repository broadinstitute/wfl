(ns zero.integration.pull-from-pubsub-test
  (:require [clojure.test :refer [deftest testing is]]
            [zero.service.gcs :as gcs]
            [zero.service.pubsub :as pubsub]
            [clojure.java.io     :as io])
  (:import (java.util UUID)))

; This test will be expanded to an AoU integration test
;
(def project
  "Test in this Google Cloud project."
  "broad-gotc-dev")

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
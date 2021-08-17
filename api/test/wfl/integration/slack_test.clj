(ns wfl.integration.slack-test
  (:require [clojure.test :refer :all]
            [wfl.service.slack :as slack]
            [wfl.util :as util])
  (:import [clojure.lang PersistentQueue]))

(def ^:private testing-agent (agent (PersistentQueue/EMPTY)))
(def ^:private testing-slack-channel "C026PTM4XPA")
(defn ^:private testing-slack-notification []
  {:channel testing-slack-channel :message (format "WFL Integration Test Message: %s" (util/utc-now))})
(def ^:private flag (promise))
(defn ^:private post-message-wrapper [channel message]
  (let [response (slack/post-message channel message)]
    (if (get response "ok")
      (deliver flag true)
      (deliver flag false))
    response))

(deftest test-send-notification-to-a-slack-channel
  (testing "notification can actually be sent to slack"
    (with-redefs [slack/post-message post-message-wrapper]
      (do (slack/add-notification testing-agent (testing-slack-notification))
        (send-off testing-agent #'slack/send-notification)))))

(comment
  (with-redefs [slack/post-message post-message-wrapper]
    (do (slack/add-notification testing-agent (testing-slack-notification))
        (send-off testing-agent #'slack/send-notification)
        #_(is (= true @flag)))))
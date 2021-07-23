(ns wfl.integration.slack-test
  (:require [clojure.test :refer :all]
            [wfl.service.slack :as slack]
            [wfl.util :as util])
  (:import [clojure.lang PersistentQueue]))

(def ^:private testing-agent (agent (PersistentQueue/EMPTY)))
(def ^:private testing-slack-channel "C026PTM4XPA")
(defn ^:private testing-slack-notification []
  {:channel testing-slack-channel :message (format "WFL Integration Test Message: %s" (util/utc-now))})
(defn ^:private post-message-assertion-wrapper [channel message]
  (let [response (slack/post-message channel message)]
    (is (get response "ok"))
    response))

(deftest test-send-notification-to-a-slack-channel
  (testing "notification can actually be sent to slack"
    (slack/add-notification testing-agent (testing-slack-notification))
    (await testing-agent)
    (with-redefs-fn
      {#'slack/start-notification-loop
       #(future (send-off % #'slack/send-notification)
          (util/sleep-seconds 1))
       #'slack/post-message
       post-message-assertion-wrapper}
      #(slack/start-notification-loop testing-agent))))

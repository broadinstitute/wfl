(ns wfl.integration.slack-test
  (:require [clojure.test :refer :all]
            [wfl.service.slack :as slack]
            [wfl.log :as log]
            [wfl.util :as util])
  (:import [clojure.lang PersistentQueue]))

(def ^:private testing-agent (agent (PersistentQueue/EMPTY)))
(def ^:private testing-slack-channel "C026PTM4XPA")
(defn ^:private testing-slack-notification []
  {:channel testing-slack-channel
   :message (format "WFL Integration Test Message: %s" (util/utc-now))})

(def ^:private notify-promise (promise))

(defn ^:private mock-send-notification
  [queue]
  (if (seq queue)
    (let [{:keys [channel message]} (peek queue)
          callback #(if (% "ok")
                      (deliver notify-promise true)
                      (deliver notify-promise false))]
      (slack/post-message channel message callback)
      (pop queue))
    queue))

(add-watch testing-agent :watcher
           (fn [_key _ref _old-state new-state]
             (when (seq new-state)
               (testing "notification is added to agent"
                 (is (= (:channel (first (seq new-state))) testing-slack-channel))))))

;; This is test is flaky on Github Actions but works fine locally
;;
(deftest ^:kaocha/pending test-send-notification-to-a-slack-channel
  (with-redefs-fn {#'slack/send-notification mock-send-notification}
    #(do (slack/add-notification testing-agent (testing-slack-notification))
         (send-off testing-agent #'slack/send-notification)
         (testing "notification can actually be sent to slack"
           (is (true? (deref notify-promise 10000 :timeout)) "Waited 10s for notification.")))))

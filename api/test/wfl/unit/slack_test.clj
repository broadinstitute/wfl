(ns wfl.unit.slack-test
  (:require [clojure.string     :as str]
            [clojure.test       :refer [deftest is testing]]
            [wfl.service.slack  :as slack]
            [wfl.tools.fixtures :as fixtures])
  (:import [clojure.lang PersistentQueue]))

(def ^:private testing-agent (agent (PersistentQueue/EMPTY)))
(defn ^:private testing-slack-notification []
  {:channel "C000BOGUS00" :message "WFL Test Message"})

(deftest test-add-notification
  (testing "notification can be added to the agent queue properly"
    (let [num-msg 10]
      (dotimes [_ num-msg]
        (slack/add-notification testing-agent (testing-slack-notification)))
      (await testing-agent)
      (is (= num-msg (count (seq @testing-agent)))))))

(deftest test-notify-watchers-only-when-enabled
  (let [notification (testing-slack-notification)
        workload     {:uuid     "workload-uuid"
                      :watchers [["slack" (:channel notification)]]}
        message      (:message notification)]
    (letfn [(mock-add-notification [maybe-enabled]
              (fn [_notifier payload]
                (if (= "enabled" maybe-enabled)
                  (do (is (= (:channel notification) (:channel payload)))
                      (is (str/includes? (:message payload) message)))
                  (throw (ex-info "Should not notify"
                                  {:maybe-enabled maybe-enabled})))))
            (verify [maybe-enabled]
              (fixtures/with-temporary-environment
                {slack/enabled-env-var-name maybe-enabled}
                #(with-redefs-fn
                   {#'slack/add-notification (mock-add-notification maybe-enabled)}
                   (fn [] (slack/notify-watchers workload message)))))]
      (testing "notifications emitted when feature enabled"
        (verify "enabled"))
      (testing "notifications not emitted when feature disabled"
        (verify "any-other-value-disables-slacking")))))

(deftest test-send-notification-does-not-throw-on-bad-response
  (let [queue (conj (PersistentQueue/EMPTY) testing-slack-notification)]
    (with-redefs-fn
      {#'slack/post-message #({:ok false :error "something_bad"})}
      #(is (= queue (slack/send-notification queue))
           "Queue should remain when posting Slack message returns error"))
    (with-redefs-fn
      {#'slack/post-message #(throw (ex-info "Unexpected throwable" {}))}
      #(is (= queue (slack/send-notification queue))
           "Queue should remain when posting Slack message throws"))))

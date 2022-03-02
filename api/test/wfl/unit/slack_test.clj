(ns wfl.unit.slack-test
  (:require [clojure.string     :as str]
            [clojure.test       :refer [deftest is testing]]
            [wfl.service.slack  :as slack]
            [wfl.tools.fixtures :as fixtures])
  (:import [clojure.lang PersistentQueue]))

(defn ^:private make-notification [tag]
  {:channel "C000BOGUS00" :message (str "WFL Test Message " tag)})

(deftest test-add-notification
  (testing "notification can be added to the agent queue properly"
    (let [message-count 10
          notifier      (agent (PersistentQueue/EMPTY))]
      (with-redefs [slack/notifier notifier]
        (dotimes [n message-count]
          (#'slack/queue-notification (make-notification n))))
      (is (await-for (* 9 message-count 1000) notifier))
      (is (= message-count (count (seq @notifier)))))))

(deftest test-notify-only-when-enabled
  (let [notification (make-notification 'test-notify-only-when-enabled)
        workload     {:uuid     "workload-uuid"
                      :watchers [["slack" (:channel notification)]]}
        message      (:message notification)]
    (letfn [(mock [maybe-enabled]
              (fn [payload]
                (when-not (= "enabled" maybe-enabled)
                  (throw (ex-info "Should not notify"
                                  {:maybe-enabled maybe-enabled})))
                (is (= (:channel notification) (:channel payload)))
                (is (str/includes? (:message payload) message))))
            (verify [maybe-enabled]
              (fixtures/with-temporary-environment
                {slack/enabled-env-var-name maybe-enabled}
                #(with-redefs
                  [slack/queue-notification (mock maybe-enabled)]
                   (slack/notify-watchers workload message))))]
      (testing "notifications emitted when feature enabled"
        (verify "enabled"))
      (testing "notifications not emitted when feature disabled"
        (verify "any-other-value-disables-slacking")))))

(deftest test-dispatch-does-not-throw
  (let [queue (conj (PersistentQueue/EMPTY)
                    (make-notification 'test-dispatch-does-not-throw))]
    (with-redefs
     [slack/post-message #({:ok false :error "something_bad"})]
      (is (= (seq queue) (seq (slack/dispatch-notification queue)))
          "Queue should remain when posting Slack message returns error"))
    (with-redefs
     [slack/post-message #(throw (ex-info "Unexpected throwable" {}))]
      (is (= (seq queue) (seq (slack/dispatch-notification queue)))
          "Queue should remain when posting Slack message throws"))))

(ns wfl.unit.slack-test
  (:require [clojure.test      :refer [deftest is testing]]
            [wfl.service.slack :as slack])
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

(deftest test-dispatch-does-not-throw
  (let [queue      (conj (PersistentQueue/EMPTY)
                         (make-notification 'test-dispatch-does-not-throw))
        post-error (constantly {:ok false :error "something_bad"})
        post-throw #(throw (ex-info "Unexpected throwable" {}))]
    (with-redefs [slack/post-message post-error]
      (is (= (seq queue) (seq (slack/dispatch-notification queue)))
          "Queue should remain when posting Slack message returns error"))
    (with-redefs [slack/post-message post-throw]
      (is (= (seq queue) (seq (slack/dispatch-notification queue)))
          "Queue should remain when posting Slack message throws"))))

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

(deftest test-notify-watchers-behind-feature-switch
  (let [notification (testing-slack-notification)
        workload     {:uuid     "workload-uuid"
                      :watchers [["slack" (:channel notification)]]}
        message      (:message notification)]
    (letfn [(mock-add-notification [switch]
              (fn [_notifier payload]
                (if (= "enabled" switch)
                  (do (is (= (:channel notification) (:channel payload)))
                      (is (str/includes? (:message payload) message)))
                  (throw (ex-info "Should not notify"
                                  {:switch switch})))))
            (verify [switch]
              (fixtures/with-temporary-environment
                {slack/feature-env-var-name switch}
                #(with-redefs-fn
                   {#'slack/add-notification (mock-add-notification switch)}
                   (fn [] (slack/notify-watchers workload message)))))]
      (run! verify ["enabled" "anything-else-is-disabled"]))))

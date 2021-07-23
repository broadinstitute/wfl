(ns wfl.unit.slack-test
  (:require [clojure.test :refer :all]
            [wfl.service.slack   :as slack])
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

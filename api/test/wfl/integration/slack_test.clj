(ns wfl.integration.slack-test
  (:require [clojure.test        :refer [deftest is testing]]
            [wfl.service.slack   :as slack]
            [wfl.tools.workloads :as workloads]
            [wfl.util            :as util])
  (:import [clojure.lang PersistentQueue]))

(def ^:private testing-agent (agent (PersistentQueue/EMPTY)))
(def ^:private testing-slack-channel "C026PTM4XPA")
(defn ^:private testing-slack-notification [fn-name]
  {:channel testing-slack-channel
   :message (format "%s `wfl.integration.slack-test/%s`: %s"
                    @workloads/project fn-name (util/utc-now))})

(def ^:private notify-promise (promise))

(defn ^:private mock-post-message-and-throw-if-failure
  [channel message]
  (let [response
        (#'slack/post-message-and-throw-if-failure-impl channel message)]
    (when (:ok response) (deliver notify-promise true))))

(add-watch
 testing-agent :watcher
 (fn [_key _ref _old-state new-state]
   (when (seq new-state)
     (testing "notification is added to agent"
       (is (= (:channel (first (seq new-state))) testing-slack-channel))))))

(deftest test-send-notification
  (with-redefs-fn
    {#'slack/post-message-and-throw-if-failure
     mock-post-message-and-throw-if-failure}
    #(do (slack/add-notification
          testing-agent
          (testing-slack-notification "test-send-notification"))
         (send-off testing-agent #'slack/send-notification)
         (testing "notification can actually be sent to slack"
           (is (true? (deref notify-promise 10000 :timeout))
               "Waited 10s for notification.")))))

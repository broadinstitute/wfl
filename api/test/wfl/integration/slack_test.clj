(ns wfl.integration.slack-test
  (:require [clojure.test        :refer [deftest is testing]]
            [wfl.service.slack   :as slack]
            [wfl.tools.workloads :as workloads]
            [wfl.util            :as util])
  (:import [clojure.lang PersistentQueue]))

(defn ^:private make-notification
  [fn-name]
  {:channel "C026PTM4XPA"
   :message (format "%s `%s/%s`: %s"
                    @workloads/project *ns* fn-name (util/utc-now))})

(deftest test-dispatch-notification
  (let [notification  (make-notification 'test-dispatch-notification)
        notifier      (agent (PersistentQueue/EMPTY))
        posted        (promise)]
    (letfn [(mock [channel message]
              (deliver posted (#'slack/post-message channel message)))]
      (with-redefs [slack/notifier              notifier
                    slack/post-message-or-throw mock]
        (#'slack/queue-notification notification)
        (send-off notifier #'slack/dispatch-notification)
        (testing "send a notification to slack"
          (let [{:keys [channel ok] :as result} (deref posted 10000 ::timeout)]
            (is (not= ::timeout result))
            (is (true? ok))
            (is (= (:channel notification) channel))))))))

(ns wfl.unit.source-test
  (:require [clojure.test :refer [deftest is testing]]
            [wfl.source   :as source]
            [wfl.util     :refer [utc-now]])
  (:import [java.sql Timestamp]
           [java.time.temporal ChronoUnit]))

(deftest test-tdr-source-should-poll?
  (let [now    (utc-now)
        source {:last_checked (Timestamp/from (.toInstant now))}]
    (letfn [(end-of-interval [min-from-interval]
              (->> #'source/tdr-source-polling-interval-minutes
                   var-get
                   (+ min-from-interval)
                   (.addTo ChronoUnit/MINUTES now)))]
      (is (false? (#'source/tdr-source-should-poll? source (end-of-interval -1))))
      (is (true? (#'source/tdr-source-should-poll? source (end-of-interval 0))))
      (is (true? (#'source/tdr-source-should-poll? source (end-of-interval 1)))))))

(deftest test-result-or-catch
  (testing "callable returns"
    (let [snapshot_id "e3a2e9f4-f620-4dcc-bf94-e3d8acf35318"
          callable    (fn [] {:id snapshot_id})
          actual      (#'source/result-or-catch callable)]
      (is (= snapshot_id (:id actual)))))
  (testing "callable throws"
    (let [message      "clj-http: status 500"
          status       500
          body-message "Failed to lock the dataset"
          body         (str "{\"message\":\"" body-message "\"}")
          callable     #(throw (ex-info message {:status status
                                                 :body   body}))
          actual       (#'source/result-or-catch callable)]
      (is (= status (:status actual)))
      (is (= body-message (get-in actual [:body :message]))))))

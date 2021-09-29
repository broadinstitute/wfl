(ns wfl.unit.source-test
  (:require [clojure.test :refer [deftest is]]
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

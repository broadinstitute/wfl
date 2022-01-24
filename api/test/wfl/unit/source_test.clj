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
              (->> #'source/tdr-source-default-polling-interval-minutes
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

(deftest test-filter-and-combine-tdr-source-details
  (let [time           "2022-01-21T08:%s:00.000000Z"
        running-rows   ["r1" "r2"]
        running        {:snapshot_creation_job_status "running"
                        :datarepo_row_ids             running-rows
                        :start_time                   (format time "00")
                        :end_time                     (format time "10")}
        succeeded-rows ["s1" "s2"]
        succeeded      {:snapshot_creation_job_status "succeeded"
                        :datarepo_row_ids             succeeded-rows
                        :start_time                   (format time "20")
                        :end_time                     (format time "30")}
        failed-rows    ["f1" "f2"]
        failed         {:snapshot_creation_job_status "failed"
                        :datarepo_row_ids             failed-rows
                        :start_time                   (format time "40")
                        :end_time                     (format time "50")}]
    (testing "No rows"
      (is (nil? (#'source/filter-and-combine-tdr-source-details
                 []))
          "Empty rows passed in should not break processing"))
    (testing "Keep all rows"
      (let [{:keys [datarepo_row_ids start_time end_time]}
            (#'source/filter-and-combine-tdr-source-details
             [succeeded])]
        (is (= succeeded-rows datarepo_row_ids)
            "succeeded rows should be kept")
        (is (= (:start_time succeeded) start_time))
        (is (= (:end_time succeeded) end_time))))
    (testing "Keep some rows"
      (let [{:keys [datarepo_row_ids start_time end_time]}
            (#'source/filter-and-combine-tdr-source-details
             [failed succeeded])]
        (is (= succeeded-rows datarepo_row_ids)
            "succeeded rows should be kept")
        (is (= (:start_time succeeded) start_time))
        (is (= (:end_time succeeded) end_time)))
      (let [{:keys [datarepo_row_ids start_time end_time]}
            (#'source/filter-and-combine-tdr-source-details
             [running succeeded failed])]
        (is (= (concat running-rows succeeded-rows) datarepo_row_ids)
            "running and succeeded rows should be kept")
        (is (= (:start_time running) start_time)
            "Earliest start time should be taken")
        (is (= (:end_time succeeded) end_time)
            "Latest end time should be taken")))
    (testing "Keep no rows"
      (is (nil? (#'source/filter-and-combine-tdr-source-details
                 [failed]))
          "failed rows should not be kept"))))

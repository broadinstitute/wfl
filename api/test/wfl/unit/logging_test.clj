(ns wfl.unit.logging-test
  "Test that logging is functional (since there's several layers of delegation)"
  (:require
   [wfl.log :as log]
   [clojure.data.json :refer [read-str]]
   [clojure.test :refer [is deftest testing]]
   [clojure.string :refer [upper-case blank?]])
  (:import java.util.regex.Pattern))

(defmulti check-message?
  (fn [expected actual]
    [(type expected) (type actual)]))

(defmethod check-message? :default
  [expected actual]
  (= expected actual))
(defmethod check-message? [Pattern String]
  [expected actual]
  (re-find expected actual))

(defn logged?
  "Test that the logs worked"
  [result severity test]
  (let [json (read-str result)]
    (and (= (get-in json ["severity"]) (-> severity name upper-case))
         (boolean (check-message? test (get-in json ["message"]))))))

;; Useful information on this file is in docs/logging.md#Testing

(deftest level-test
  (testing "basic logging levels"
    (with-redefs [log/logging-level (atom :debug)]
      (is (logged? (with-out-str (log/info "Hello World!")) :info "Hello World!"))
      (is (logged? (with-out-str (log/warn "This is a warning")) :warning "This is a warning"))
      (is (logged? (with-out-str (log/error "This is an error")) :error "This is an error"))
      (is (logged? (with-out-str (log/debug "This is just a debugging message")) :debug "This is just a debugging message")))))

(deftest severity-level-filtering-test
  (testing "test current logging level correctly ignores lesser levels"
    (with-redefs [log/logging-level (atom :info)]
      (is (blank? (with-out-str (log/debug "Debug Message"))))
      (is (logged? (with-out-str (log/info "Info Message")) :info "Info Message")))))

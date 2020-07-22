(ns zero.unit.logging-test
  "Test that logging is functional (since there's several layers of delegation)"
  (:require
    [clojure.tools.logging.readable :as log]
    [clojure.tools.logging.test :refer [logged? with-log]]
    [clojure.test :refer [is deftest testing]]))

;; Useful information on this file is in docs/logging.md#Testing

(deftest level-test
  (testing "basic logging levels"
    (with-log
      (log/info "Hello World!")
      (is (logged? 'zero.unit.logging-test :info "Hello World!"))
      (log/warn "This is a warning")
      (is (logged? 'zero.unit.logging-test :warn "This is a warning"))
      (log/error "and this is an error")
      (is (logged? 'zero.unit.logging-test :error "and this is an error"))
      (log/debug "This is just a debugging message")
      (is (logged? 'zero.unit.logging-test :debug "This is just a debugging message"))
      (log/trace "This is a trace")
      (is (logged? 'zero.unit.logging-test :trace "This is a trace")))))

(deftest format-test
  (testing "formatting (more for demonstration than assurance)"
    (with-log
      (log/info "abc" 6 "abcd")
      (is (logged? 'zero.unit.logging-test :info "abc 6 abcd"))
      (log/infof "%s %s" "abc" 123)
      (is (logged? 'zero.unit.logging-test :info "\"abc\" 123")))))
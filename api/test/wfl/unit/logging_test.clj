(ns wfl.unit.logging-test
  "Test that our overcomplicated logging namespace works."
  (:require [clojure.test      :refer [is deftest testing]]
            [clojure.data.json :as json]
            [clojure.string    :as str]
            [wfl.log           :as log])
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
  "Test that the logs worked."
  [result severity test]
  (let [json (json/read-str result :key-fn keyword)]
    (and (= (:severity json) (-> severity name str/upper-case))
         (boolean (check-message? test (get-in json [:message :result]))))))

;; Useful information on this file is in docs/logging.md#Testing

(deftest level-test
  (testing "basic logging levels"
    (with-redefs [log/active-level-predicate (atom (:debug @#'log/active-map))]
      (is (logged? (with-out-str (log/info    "ofni"))    :info    "ofni"))
      (is (logged? (with-out-str (log/warning "gninraw")) :warning "gninraw"))
      (is (logged? (with-out-str (log/error   "rorre"))   :error   "rorre"))
      (is (logged? (with-out-str (log/debug   "gubed"))   :debug   "gubed")))))

(deftest severity-level-filtering-test
  (testing "test current logging level correctly ignores lesser levels"
    (with-redefs
     [log/active-level-predicate (atom (:info @#'log/active-map))]
      (is (str/blank? (with-out-str (log/debug "Debug Message"))))
      (is (logged?    (with-out-str (log/info  "Info Message"))
                      :info "Info Message")))))

(deftest exception-test
  (testing "exceptions can be serialized as JSON"
    (let [oops   (ex-info "Oops!" {:why "I did it again."}
                          (ex-info "I played with your heart."
                                   {:why "Got lost in the game."}
                                   (ex-info "Oh baby, baby."
                                            {:oops "You think I'm in love."
                                             :that "I'm sent from above."})))
          log    (json/read-str (with-out-str (log/alert oops)) :key-fn keyword)
          result (get-in log [:message :result])]
      (is (= "ALERT"                (get-in log    [:severity])))
      (is (= "I did it again."      (get-in result [:via 0 :data :why])))
      (is (= "Oh baby, baby."       (get-in result [:cause])))
      (is (= "I'm sent from above." (get-in result [:data :that]))))))

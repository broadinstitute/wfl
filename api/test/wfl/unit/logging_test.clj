(ns wfl.unit.logging-test
  "Test that our overcomplicated logging works."
  (:require [clojure.test          :refer [is deftest testing]]
            [clojure.data.json     :as json]
            [clojure.string        :as str]
            [wfl.log               :as log]
            [wfl.service.firecloud :as firecloud])
  (:import [clojure.lang ExceptionInfo]
           [java.util.regex Pattern]))

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
  "Test that the expected logs happened."
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

(deftest demo-log-with-json-incompatible-http-client
  (let [workspace  "wfl-dev/Illumina-Genotyping-Array-Templateecf09d017b8d4033bab8d5feeae86987"
        submission "6ac9a307-5eeb-4e20-9321-b32d0f80adf4"
        thrown    (try
                     (firecloud/get-submission workspace submission)
                     (catch ExceptionInfo x
                       (println "Caught exception when fetching submission for deleted workspace")
                       (println (str x))
                       x))
        remove-bad (ex-info (.getMessage thrown)
                            (dissoc (ex-data thrown) :http-client)
                            (.getCause thrown))]
    (try
      (-> thrown log/alert with-out-str (json/read-str :key-fn keyword))
      (println "We shouldn't be here, shouldn't be able to log with http-object...")
      (catch Exception x
        (println "Can't log when including http-object :-(")
        (println x)))
    (try
      (-> remove-bad log/alert with-out-str (json/read-str :key-fn keyword))
      (println "Can log when excluding http-object!")
      (catch Exception x
        (println "We shouldn't be here, should be able to log without http-object...")
        (println x)))))

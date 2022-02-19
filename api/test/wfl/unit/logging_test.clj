(ns wfl.unit.logging-test
  "Test that our overcomplicated logging works."
  (:require [clojure.test        :refer [is deftest testing]]
            [clojure.data.json   :as json]
            [clojure.edn         :as edn]
            [clojure.string      :as str]
            [wfl.log             :as log]
            [wfl.tools.endpoints :as endpoints])
  (:import [java.util.regex Pattern]))

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

(deftest endpoint
  (testing "the /logging_level endpoint works"
    (let [{:keys [level] :as init} (endpoints/get-logging-level)]
      (is (#'log/level-string? level))
      (try (let [other  (-> level set
                            (remove @#'log/levels)
                            first name str/upper-case)
                 posted (endpoints/post-logging-level other)
                 got    (endpoints/get-logging-level)]
             (is (= got posted {:level other})))
           (finally (endpoints/post-logging-level level)))
      (is (= init (endpoints/get-logging-level))))))

(deftest level-test
  (testing "basic logging levels"
    (with-redefs [log/active-level-predicate (atom (:debug @#'log/active-map))]
      (is (logged? (with-out-str (log/info    "ofni"))    :info    "ofni"))
      (is (logged? (with-out-str (log/warning "gninraw")) :warning "gninraw"))
      (is (logged? (with-out-str (log/error   "rorre"))   :error   "rorre"))
      (is (logged? (with-out-str (log/debug   "gubed"))   :debug   "gubed")))))

(deftest severity-level-filtering-test
  (testing "logging level ignores lesser severities"
    (with-redefs
     [log/active-level-predicate (atom (:info @#'log/active-map))]
      (is (str/blank? (with-out-str (log/debug "Debug Message"))))
      (is (logged?    (with-out-str (log/info  "Info Message"))
                      :info "Info Message")))))

(deftest log-almost-anything
  (testing "WFL can log almost anything"
    (let [tagged (tagged-literal 'object :fnord)
          exinfo (ex-info "log ex-info" {:tagged tagged})]
      (try
        (is (not (str/includes?
                  (with-out-str
                    (log/error false :what? true)
                    (log/error nil)
                    (log/error  Math/E :set #{false nil nil? true Math/E})
                    (log/error 23 :ok 'OK! :never "mind")
                    (log/error (/ 22 7) :pi :pi :pi 'pie)
                    (log/error \X 'hey 'hey 'hey 'ho)
                    (log/error log/log :nope #_"nope" 'nope #_'ok)
                    (log/error '(comment comment) :no (comment comment))
                    (log/error tagged)
                    (log/error (str ex-info "log ex-info" {:tagged tagged}))
                    (log/error (ex-info "log ex-info" {:tagged tagged}))
                    (log/error (type tagged))
                    (log/error (type (type tagged)))
                    (log/error (ancestors (type tagged)))
                    (log/error {(type tagged) tagged})
                    (log/error ["There is a character C here:" \C])
                    (log/error (rest [exinfo exinfo exinfo exinfo]))
                    (log/error #{:a :symbol `symbol})
                    (log/error #{(type tagged) tagged})
                    (log/error (list \X tagged (list) (type tagged) list)))
                  "tried-to-log")))
        (catch Throwable t
          (log/emergency {:t t})
          (is false))))))

(defrecord WTF [tag value])
(def ^:private wtf (partial edn/read-string {:default ->WTF}))

(deftest can-log-exception
  (testing "exceptions can be serialized as JSON"
    (let [oops   (ex-info "Oops!" {:why "I did it again."}
                          (ex-info "I played with your heart."
                                   {:why "Got lost in the game."}
                                   (ex-info "Oh baby, baby."
                                            {:oops "You think I'm in love."
                                             :that "I'm sent from above."})))
          log    (json/read-str (with-out-str (log/alert oops)) :key-fn keyword)
          result (:value (wtf (get-in log [:message :result])))]
      (is (= "ALERT"                (get-in log    [:severity])))
      (is (= "I did it again."      (get-in result [:via 0 :data :why])))
      (is (= "Oh baby, baby."       (get-in result [:cause])))
      (is (= "I'm sent from above." (get-in result [:data :that]))))))

;; Cannot (log/emergency ##NaN) because cljfmt chokes on it.
;;
(deftest log-has-backstop
  (testing "JSON-incompatible input is stringified"
    (let [log (with-out-str (log/emergency (Math/sqrt -1)))
          edn (wtf (json/read-str log :key-fn keyword))
          {:keys [tried-to-log cause]} edn]
      (is (map? tried-to-log))
      (is (map? cause))
      (let [{:keys [::log/message ::log/severity]} tried-to-log]
        (is (map? message))
        (is (= "EMERGENCY" severity)))
      (let [{:keys [tag value]} cause]
        (is (= 'error tag))
        (is (= "JSON error: cannot write Double NaN" (:cause value)))))))

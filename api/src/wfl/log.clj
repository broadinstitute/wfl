(ns wfl.log
  "Logging for WFL"
  (:require [clojure.core]
            [clojure.data.json :refer [write-str]]
            [clojure.string :refer [upper-case]])
  (:import [java.sql Timestamp]
           [java.time OffsetDateTime]))

(defn ^:private googleize-field
  "Prepends the google logging domain to the fields that require it for Google Logging."
  [field]
  (let [field-name (name field)]
    (if (#{"labels" "insertId" "operation" "sourceLocation" "spanId" "trace"} field-name)
      (str "logging.googleapis.com/" (name field-name))
      field-name)))

(defn log
  "Write a log entry to standard output."
  [severity message & {:as additional-fields}]
  (let [severity-upper (-> severity name upper-case)]
    (-> (merge {:severity severity-upper
                :message message
                :timestamp (Timestamp/from (.toInstant (OffsetDateTime/now)))}
               additional-fields)
        (write-str :key-fn googleize-field :escape-slash false)
        (.toString)
        (println))))

(defmacro info
  "Log as Information"
  [message & more]
  `(log :info (print-str ~message ~@more)))

(defmacro infof
  "Log as information with formatting"
  [message & more]
  `(log :info (format ~message ~@more)))

(defmacro warn
  "Log as a Warning"
  [message & more]
  `(log :warning (print-str ~message ~@more)))

(defmacro warnf
  "Log as a Warning with formatting"
  [message & more]
  `(log :warning (format ~message ~@more)))

(defmacro debug
  "Log as Debug"
  [message & more]
  `(log :debug (print-str ~message ~@more)))

(defmacro debugf
  "Log as Debug with formatting"
  [message & more]
  `(log :debug (format ~message ~@more)))

(defmacro error
  "Log as Error"
  [message & more]
  `(log :error (print-str ~message ~@more)))

(defmacro errorf
  "Log as Error with formatting"
  [message & more]
  `(log :error (format ~message ~@more)))

(defmacro trace
  "Log as Trace. Because there isn't a TRACE Severity in GCP Logging, this will just add an entry as DEBUG"
  [message & more]
  `(log :debug (print-str ~message ~@more)))

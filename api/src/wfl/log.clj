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
    (if (#{"labels" "insertId" "operation" "sourceLocation" "spanId" "trace"})
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
        (write-str :key-fn googleize-field :espace-slash false)
        (.toString)
        (println))))

(defmacro info
  "Log as Information"
  [message & more]
  `(log :info (print-str ~message ~@more)))

(defmacro warn
  "Log as a Warning"
  [message & more]
  `(log :warning (print-str ~message ~@more)))

(defmacro debug
  "Log as Debug"
  [message & more]
  `(log :debug (print-str ~message ~@more)))

(defmacro error
  "Log as Error"
  [message & more]
  `(log :error (print-str ~message ~@more)))

(comment
  (info "my message" "with" "stuff")
  (warn "my message")
  (debug "my message")
  (error "my message")
  (log :info "test" :labels {:test "HI"}))

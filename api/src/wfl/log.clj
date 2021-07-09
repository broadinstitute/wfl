(ns wfl.log
  "Logging for WFL"
  (:require [clojure.data.json :as json]
            [clojure.string    :as str])
  (:import [java.sql Timestamp]
           [java.time OffsetDateTime]))

(def ^:private default-key-fn (:key-fn json/default-write-options))

(def ^:private googleize-key?
  "Prepend the Google Logging domain to these keys."
  #{"insertId" "labels" "operation" "sourceLocation" "spanId" "trace"})

(defn ^:private key-fn
  "Preserve the namespace of `key` when qualified."
  [key]
  (default-key-fn
   (cond (qualified-keyword? key) (str (namespace key) \/ (name key))
         (googleize-key? key)     (str "logging.googleapis.com" \/ key)
         :else                    key)))

(defprotocol Logger
  ""
  (write [logger json] "Writes the log using the given logger."))

(def disabled-logger
  "A logger that does not log."
  (reify Logger
    (write [_ _])))

(def stdout-logger
  "A logger to write to standard output"
  (reify Logger
    (write [logger edn]
      (println (json/write-str edn :key-fn key-fn :escape-slash false)))))

(def ^:dynamic *logger*
  ""
  stdout-logger)

(defn log
  "Write a log entry."
  [severity message & {:as additional-fields}]
  (write *logger* (merge {:severity (-> severity name str/upper-case)
                          :message message
                          :timestamp (Timestamp/from (.toInstant (OffsetDateTime/now)))}
                         additional-fields)))

(defmacro info
  "Log as Information"
  [message & more]
  `(log "INFO" (print-str ~message ~@more)))

(defmacro infof
  "Log as information with formatting"
  [message & more]
  `(log "INFO" (format ~message ~@more)))

(defmacro warn
  "Log as a Warning"
  [message & more]
  `(log "WARNING" (print-str ~message ~@more)))

(defmacro warnf
  "Log as a Warning with formatting"
  [message & more]
  `(log "WARNING" (format ~message ~@more)))

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
  `(log "ERROR" (print-str ~message ~@more)))

(defmacro errorf
  "Log as Error with formatting"
  [message & more]
  `(log "ERROR" (format ~message ~@more)))

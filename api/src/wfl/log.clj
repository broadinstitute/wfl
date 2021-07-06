(ns wfl.log
  "Logging for WFL"
  (:require [clojure.core]
            [clojure.data.json :refer [write-str]])
  (:import [java.sql Timestamp]
           [java.time OffsetDateTime]))

(defn googleize-field
  "Prepends the google logging domain to the fields that require it for Google Logging."
  [field]
  (let [field-name (name field)]
    (if (some #(= field-name %) '("labels" "insertId" "operation" "sourceLocation" "spanId" "trace")) (str "logging.googleapis.com/" (name field-name))
        field-name)))

(defn write-to-stdout
  "Writes the output to standard output as a json string."
  [json]
  (-> (write-str json :key-fn googleize-field :escape-slash false)
      (.toString)
      (println)))

(defn get-timestamp
  "Gets timestamp for the json"
  []
  (Timestamp/from (.toInstant (OffsetDateTime/now))))

(defn add-common-fields
  "Builds the common message before applying type specific fields."
  [json message]
  (merge json
         {:message message
          :timestamp (get-timestamp)}))

(defn log
  "Write a log entry to standard output."
  [severity message & {:as additional-fields}]
  (let [severity-upper (.toUpperCase severity)]
    (if (not (some #(= severity-upper %) '("DEFAULT" "DEBUG" "INFO" "NOTICE" "WARNING" "ERROR" "CRITICAL" "ALERT" "EMERGENCY")))
      (throw (ex-info "Invalid severity provided to logging function." {:severity severity-upper}))
      (->> (add-common-fields {:severity severity-upper} message)
           (merge additional-fields)
           (write-to-stdout)))))

(defmacro info
  "Log as Information"
  [message & more]
  `(->> (print-str ~message ~@more)
        (log "INFO")))

(defmacro warn
  "Log as a Warning"
  [message & more]
  `(->> (print-str ~message ~@more)
        (log "WARNING")))

(defmacro debug
  "Log as Debug"
  [message & more]
  `(->> (print-str ~message ~@more)
        (log "DEBUG")))

(defmacro error
  "Log as Error"
  [message & more]
  `(->> (print-str ~message ~@more)
        (log "ERROR")))
(comment
  (info "my message" "with" "stuff")
  (warn "my message")
  (debug "my message")
  (error "my message")
  (log "INFO" "test" :labels {:test "HI"}))

(ns wfl.log
  "Logging for WFL. The severity levels provided here are based off of
   the severity levels supported by GCP's Stackdriver. A list of the supported
   levels can be found here: https://cloud.google.com/logging/docs/reference/v2/rest/v2/LogEntry#LogSeverity"
  (:require [clojure.data.json              :as json]
            [clojure.string                 :as str]
            [clojure.spec.alpha             :as s])
  (:import [java.time Instant]))

(def levels
  "A list of the available logging levels the application can use."
  [:debug :info :notice :warning :error :alert :critical :emergency])

(defn levels-contains?
  "Validate that the provided label exists."
  [s]
  (let [level (-> s str/lower-case keyword)]
    (some #(= level %) levels)))
(s/def ::level (s/and string? levels-contains?))
(s/def ::logging-level-request (s/keys :req-un [::level]))
(s/def ::logging-level-response (s/keys :req-un [::level]))

(defn ^:private key-fn
  "Preserve the namespace of `key` when qualified."
  [key]
  ((:key-fn json/default-write-options)
   (if (qualified-keyword? key)
     (str (namespace key) \/ (name key))
     key)))

(defprotocol Logger
  "Log `edn` to `logger` as JSON."
  (-write [logger edn]))

(def disabled-logger
  "A logger that does not log."
  (reify Logger
    (-write [_ _])))

(def stdout-logger
  "A logger to write to standard output"
  (reify Logger
    (-write [logger edn]
      (-> edn
          (json/write-str :key-fn key-fn :escape-slash false)
          println))))

(def ^:dynamic *logger*
  "The logger now."
  stdout-logger)

(def logging-level
  "The current logging level of the application."
  (atom :info))

(defmacro log
  "Log `expression` with `severity` and a optional set of special
   fields to provide more information about a logging message.

   A detailed explanation of the fields and their meaning can be found
   here: https://cloud.google.com/logging/docs/agent/logging/configuration#special-fields

  :httpRequest  A structured record for the Http Request that was made

  :logging.googleapis.com/insertId    An optional unique identifier. Logs with the same identifier and timestamp will be considered duplicates in Google Logging.

  :logging.googleapis.com/labels    A map of key value pairs that can be searched on in Google Logging.

  :logging.googleapis.com/operation    Additional information about a potentially long-running operation with which a log entry is associated.

  :logging.googleapis.com/trace    Resource name of the trace associated with the log entry if any.

  :logging.googleapis.com/spanId    The span ID within the trace associated with the log entry."
  [severity expression & {:as additional-fields}]
  (let [{:keys [line]} (meta &form)]
    `(let [x# ~expression
           [excl# incl#] (split-with (complement #{@logging-level}) levels)]
       (when (contains? (set incl#) ~severity)
         (-write *logger*
                 (merge {:timestamp (Instant/now)
                         :severity ~(-> severity name str/upper-case)
                         :message x#
                         :logging.googleapis.com/sourceLocation
                         {:file ~*file* :line ~line}}
                        ~additional-fields)))
       nil)))

(defmacro debug
  "Log `expression` for debugging.
   This is for debug or trace information."
  ([expression]
   (let [{:keys [line]} (meta &form)] `(debug ~expression ~line)))
  ([expression line]
   `(log :debug ~expression :logging.googleapis.com/sourceLocation {:file ~*file* :line ~line})))

(defmacro info
  "Log `expression` as information.
   Used for routine information, such as ongoing status or performance."
  ([expression]
   (let [{:keys [line]} (meta &form)] `(info ~expression ~line)))
  ([expression line]
   `(log :info ~expression :logging.googleapis.com/sourceLocation {:file ~*file* :line ~line})))

(defmacro notice
  "Log `expression` as a notice.
   Used for normal but significant events, such as start up,
   shut down, or a configuration change."
  ([expression]
   (let [{:keys [line]} (meta &form)] `(notice ~expression ~line)))
  ([expression line]
   `(log :notice ~expression :logging.googleapis.com/sourceLocation {:file ~*file* :line ~line})))

(defmacro warn
  "Log `expression` as a warning.
   Used for warning events, which might cause problems."
  ([expression]
   (let [{:keys [line]} (meta &form)] `(warn ~expression ~line)))
  ([expression line]
   `(log :warning ~expression :logging.googleapis.com/sourceLocation {:file ~*file* :line ~line})))

(defmacro error
  "Log `expression` as an error.
   Used for events that are likely to cause problems."
  ([expression]
   (let [{:keys [line]} (meta &form)] `(error ~expression ~line)))
  ([expression line]
   `(log :error ~expression :logging.googleapis.com/sourceLocation {:file ~*file* :line ~line})))

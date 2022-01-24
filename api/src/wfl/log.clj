(ns wfl.log
  "Logging for WFL. The severity levels provided here are based off of
   the severity levels supported by GCP's Stackdriver. A list of the supported
   levels can be found here:
   https://cloud.google.com/logging/docs/reference/v2/rest/v2/LogEntry#LogSeverity"
  (:require [clojure.data.json  :as json]
            [clojure.spec.alpha :as s]
            [clojure.string     :as str])
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

;; HACK: Override how JSONWriter handles Throwables.
;; json/write-object is private and handles java.util.Map.
;;
(defn ^:private write-throwable
  "Write the Throwable X to OUT as JSON with OPTIONS."
  [x out options]
  (#'json/write-object (Throwable->map x) out (assoc options
                                                     :escape-slash false
                                                     :key-fn       key-fn)))

(extend java.lang.Throwable json/JSONWriter {:-write write-throwable})

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
    (-write [_logger edn]
      (let [json-write-str
            #(json/write-str
              %
              :escape-slash false
              :key-fn       key-fn)
            to-log
            (try (json-write-str edn)
                 (catch Throwable t
                   (json-write-str {:tried-to-log (str edn)
                                    :cause        (str t)})))]
        (println to-log)))))

(def ^:dynamic *logger*
  "The logger now."
  stdout-logger)

(def logging-level
  "The current logging level of the application."
  (atom :info))

(defmacro log
  "Log `expression` with `severity` and optional fields to provide more
  information in a logging message.

  An explanation of the fields can be found here:
  https://cloud.google.com/logging/docs/agent/logging/configuration#special-fields

  :httpRequest
  A structured record for the Http Request that was made

  :logging.googleapis.com/insertId
  Logs with the same insertId and timestamp
  are considered duplicates in Google Logging.

  :logging.googleapis.com/labels
  A map of key value pairs that can be searched on in Google Logging.

  :logging.googleapis.com/operation
  Additional information about a potentially long-running operation
  with which a log entry is associated.

  :logging.googleapis.com/spanId
  The span ID within the trace associated with the log entry.

  :logging.googleapis.com/trace
  Resource name of the trace associated with the log entry if any."
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
  ([expression & {:as labels}]
   `(log :debug ~expression :logging.googleapis.com/labels ~labels)))

(defmacro info
  "Log `expression` as information.
   Used for routine information, such as ongoing status or performance."
  ([expression & {:as labels}]
   `(log :info ~expression :logging.googleapis.com/labels ~labels)))

(defmacro notice
  "Log `expression` as a notice.
   Used for normal but significant events, such as start up,
   shut down, or a configuration change."
  ([expression & {:as labels}]
   `(log :notice ~expression :logging.googleapis.com/labels ~labels)))

(defmacro warn
  "Log `expression` as a warning.
   Used for warning events, which might cause problems."
  ([expression & {:as labels}]
   `(log :warning ~expression :logging.googleapis.com/labels ~labels)))

(defmacro error
  "Log `expression` as an error.
   Used for events that are likely to cause problems."
  ([expression & {:as labels}]
   `(log :error ~expression :logging.googleapis.com/labels ~labels)))

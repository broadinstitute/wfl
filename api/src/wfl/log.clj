(ns wfl.log
  "GCP Stackdriver logging for WFL."
  (:require [clojure.data.json  :as json]
            [clojure.spec.alpha :as s]
            [clojure.string     :as str])
  (:import [java.time Instant]))

(alias 'lgc (ns-name (create-ns 'logging.googleapis.com)))

;; https://cloud.google.com/logging/docs/reference/v2/rest/v2/LogEntry#LogSeverity
;;
(def levels
  "The log severity level keywords by increasing severity."
  [:debug :info :notice :warning :error :critical :alert :emergency])

(def ^:private level?
  "The set of log severity level keywords."
  (set levels))

(defn ^:private level-string?
  "True when `level` string names a log severity level."
  [level]
  (some level? (-> level str/lower-case keyword)))

(s/def ::level-string   (s/and string? level-string?))
(s/def ::level-request  (s/keys :req-un [::level-string]))
(s/def ::level-response (s/keys :req-un [::level-string]))

;; https://cloud.google.com/logging/docs/agent/logging/configuration#special-fields
;;
(def google-fields
  "Map WFL's log field names to what Stackdriver recognizes."
  {::httpRequest :httpRequest
   ::insertId    ::lgc/insertId
   ::labels      ::lgc/labels
   ::operation   ::lgc/operation
   ::spanId      ::lgc/spanId
   ::trace       ::lgc/trace})

(defn ^:private key-fn
  "Preserve the namespace of `key` when qualified."
  [key]
  ((:key-fn json/default-write-options)
   (let [googled (google-fields key key)]
     (if (qualified-keyword? googled)
       (str (namespace googled) \/ (name googled))
       key))))

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
    (-write [logger edn]
      (-> edn
          (json/write-str :escape-slash false
                          :key-fn       key-fn)
          println))))

(def ^:dynamic *logger*
  "The logger now."
  stdout-logger)

(def logging-level
  "The current logging level of the application."
  (atom :info))

(defmacro log
  "Log `expression` with `severity` and `additional-fields`."
  [severity expression & {:as additional-fields}]
  (let [{:keys [line]} (meta &form)]
    `(let [x# ~expression
           [excl# incl#] (split-with (complement #{@logging-level}) levels)]
       (when (contains? (set incl#) ~severity)
         (-write *logger*
                 (merge {:timestamp (Instant/now)
                         :severity ~(-> severity name str/upper-case)
                         :message x#
                         ::lgc/sourceLocation
                         {:file ~*file* :line ~line}}
                        ~additional-fields)))
       nil)))

(defmacro debug
  "Log `expression` for debugging.
   This is for debug or trace information."
  ([expression & {:as labels}]
   `(log :debug ~expression ::lgc/labels ~labels)))

(defmacro info
  "Log `expression` as information.
   Used for routine information, such as ongoing status or performance."
  ([expression & {:as labels}]
   `(log :info ~expression ::lgc/labels ~labels)))

(defmacro notice
  "Log `expression` as a notice.
   Used for normal but significant events, such as start up,
   shut down, or a configuration change."
  ([expression & {:as labels}]
   `(log :notice ~expression ::lgc/labels ~labels)))

(defmacro warn
  "Log `expression` as a warning.
   Used for warning events, which might cause problems."
  ([expression & {:as labels}]
   `(log :warning ~expression ::lgc/labels ~labels)))

(defmacro error
  "Log `expression` as an error.
   Used for events that are likely to cause problems."
  ([expression & {:as labels}]
   `(log :error ~expression ::lgc/labels ~labels)))

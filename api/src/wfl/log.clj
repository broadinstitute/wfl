(ns wfl.log
  "Log to GCP Stackdriver."
  (:require [clojure.data.json  :as json]
            [clojure.spec.alpha :as s]
            [clojure.string     :as str])
  (:import [java.time Instant]))

;; https://cloud.google.com/logging/docs/reference/v2/rest/v2/LogEntry#LogSeverity
;;
(def ^:private levels
  "The log level keywords by increasing severity."
  [:debug :info :notice :warning :error :critical :alert :emergency])

(def ^:private level?
  "The set of log level keywords."
  (set levels))

(defn ^:private level-string?
  "True when `level` string names a log level."
  [level]
  (some level? (-> level str/lower-case keyword)))

(s/def ::level-string (s/and string? level-string?))
(s/def ::level-request   (s/keys :req-un [::level-string]))
(s/def ::level-response  (s/keys :req-un [::level-string]))

(def ^:private active-map
  "Map a level keyword to a set of active levels."
  (loop [sofar {} levels levels]
    (if-let [level (first levels)]
      (recur (assoc sofar level (set levels)) (rest levels))
      sofar)))

(def active-level-predicate
  "The current active level predicate."
  (atom (:error active-map)))

(defn set-active-level
  "Set `active-level?` for the `level` string."
  [level]
  (reset! active-level-predicate
          (-> (if (empty? level) "info" level)
              str/lower-case keyword active-map)))

;; https://cloud.google.com/logging/docs/agent/logging/configuration#special-fields
;;
(def ^:private google-fields
  "Map WFL's log field names to what Stackdriver recognizes."
  {::httpRequest    :httpRequest
   ::insertId       :logging.googleapis.com/insertId
   ::labels         :logging.googleapis.com/labels
   ::message        :message
   ::operation      :logging.googleapis.com/operation
   ::severity       :severity
   ::sourceLocation :logging.googleapis.com/sourceLocation
   ::spanId         :logging.googleapis.com/spanId
   ::time           :time
   ::trace          :logging.googleapis.com/trace})

(defn ^:private key-fn
  "Preserve the namespace of `key` when qualified."
  [key]
  ((:key-fn json/default-write-options)
   (let [googled (google-fields key key)]
     (if (qualified-keyword? googled)
       (str (namespace googled) \/ (name googled))
       key))))

(defn ^:private value-fn
  "Preserve more of `value` EDN somehow."
  [key value]
  ((:value-fn json/default-write-options)
   key
   (cond (char?    value) (pr-str value)
         (keyword? value) (pr-str value)
         (list?    value) (pr-str value)
         (set?     value) (pr-str value)
         :else                    value)))

;; HACK: Override how JSONWriter handles Throwables.
;; json/write-object is private and handles java.util.Map.
;;
(defn ^:private write-throwable
  "Write the Throwable X to OUT as JSON with OPTIONS."
  [x out options]
  (#'json/write-object (Throwable->map x) out
                       (assoc options
                              :escape-slash false
                              :key-fn       key-fn
                              :value-fn     value-fn)))

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
      (-> edn
          (json/write-str :escape-slash false
                          :key-fn       key-fn
                          :value-fn     value-fn)
          println))))

(def ^:dynamic *logger*
  "The logger now."
  stdout-logger)

(defn log
  "Log `context`, `result`, and `more` at `level` with `severity`."
  [level context severity result & {:as more}]
  (let [{:keys [column expression file line namespace]} context]
    (when (@active-level-predicate level)
      (-write *logger*
              {::message        (merge {:column    column
                                        :namespace namespace
                                        :result    result} more)
               ::severity       severity
               ::sourceLocation {:file     file
                                 :function expression
                                 :line     line}
               ::time            (Instant/now)}))
    #_result))                          ; Not ready for this yet.

;; Generate these 8 macros from the wfl.log/levels keywords.

(defmacro debug
  [expression & more]
  `(let [result# ~expression]
     (log :debug (assoc ~(meta &form)
                        :expression '~expression
                        :file       ~*file*
                        :namespace  '~(ns-name *ns*))
          "DEBUG" result# ~@more)))

(defmacro info
  [expression & more]
  `(let [result# ~expression]
     (log :info (assoc ~(meta &form)
                       :expression '~expression
                       :file       ~*file*
                       :namespace  '~(ns-name *ns*))
          "INFO" result# ~@more)))

(defmacro notice
  [expression & more]
  `(let [result# ~expression]
     (log :notice (assoc ~(meta &form)
                         :expression '~expression
                         :file       ~*file*
                         :namespace  '~(ns-name *ns*))
          "NOTICE" result# ~@more)))

(defmacro warning
  [expression & more]
  `(let [result# ~expression]
     (log :warning (assoc ~(meta &form)
                          :expression '~expression
                          :file       ~*file*
                          :namespace  '~(ns-name *ns*))
          "WARNING" result# ~@more)))

(defmacro error
  [expression & more]
  `(let [result# ~expression]
     (log :error (assoc ~(meta &form)
                        :expression '~expression
                        :file       ~*file*
                        :namespace  '~(ns-name *ns*))
          "ERROR" result# ~@more)))

(defmacro critical
  [expression & more]
  `(let [result# ~expression]
     (log :critical (assoc ~(meta &form)
                           :expression '~expression
                           :file       ~*file*
                           :namespace  '~(ns-name *ns*))
          "CRITICAL" result# ~@more)))

(defmacro alert
  [expression & more]
  `(let [result# ~expression]
     (log :alert (assoc ~(meta &form)
                        :expression '~expression
                        :file       ~*file*
                        :namespace  '~(ns-name *ns*))
          "ALERT" result# ~@more)))

(defmacro emergency
  [expression & more]
  `(let [result# ~expression]
     (log :emergency (assoc ~(meta &form)
                            :expression '~expression
                            :file       ~*file*
                            :namespace  '~(ns-name *ns*))
          "EMERGENCY" result# ~@more)))

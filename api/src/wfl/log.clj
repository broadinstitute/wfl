(ns wfl.log
  "Log to GCP Stackdriver."
  (:require [clojure.data.json  :as json]
            [clojure.spec.alpha :as s]
            [clojure.string     :as str]
            [wfl.debug          :as debug])
  (:import [java.time Instant]))

(alias 'lgc (ns-name (create-ns 'logging.googleapis.com)))

;; https://cloud.google.com/logging/docs/reference/v2/rest/v2/LogEntry#LogSeverity
;;
(def ^:private severities
  "The log severity keywords by increasing severity."
  [:debug :info :notice :warning :error :critical :alert :emergency])

(def ^:private severity?
  "The set of log severity keywords."
  (set severities))

(defn ^:private severity-string?
  "True when `severity` string names a log severity."
  [severity]
  (some severity? (-> severity str/lower-case keyword)))

(s/def ::severity-string (s/and string? severity-string?))
(s/def ::level-request   (s/keys :req-un [::severity-string]))
(s/def ::level-response  (s/keys :req-un [::severity-string]))

(def ^:private active-map
  "Map a severity keyword to a set of active severities."
  (loop [sofar {} severities severities]
    (if-let [severity (first severities)]
      (recur (assoc sofar severity (set severities)) (rest severities))
      sofar)))

(def active-severity-predicate
  "The current active severity predicate."
  (atom (:info active-map)))

(defn set-active-severity
  "Set `active-severity?` for the `severity` string."
  [severity]
  (reset! active-severity-predicate
          (-> (if (empty? severity) "info" severity)
              str/lower-case keyword active-map)))

;; https://cloud.google.com/logging/docs/agent/logging/configuration#special-fields
;;
(def ^:private google-fields
  "Map WFL's log field names to what Stackdriver recognizes."
  {::httpRequest    :httpRequest
   ::insertId       ::lgc/insertId
   ::labels         ::lgc/labels
   ::message        :message
   ::operation      ::lgc/operation
   ::severity       :severity
   ::sourceLocation ::lgc/sourceLocation
   ::spanId         ::lgc/spanId
   ::time           :time
   ::trace          ::lgc/trace})

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
  '(wfl.debug/trace [key value])
  ((:value-fn json/default-write-options)
   key
   (cond (char?    value) (pr-str value)
         (keyword? value) (pr-str value)
         (list?    value) (pr-str value)
         ;; (seq?     value) (pr-str value)
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
    (-write [logger edn]
      '(debug/trace [logger edn])
      (-> edn
          (json/write-str :escape-slash false
                          :key-fn       key-fn
                          :value-fn     value-fn)
          println))))

(def ^:dynamic *logger*
  "The logger now."
  stdout-logger)

(defn log
  [context severity result]
  (wfl.debug/trace [context severity result])
  (let [{:keys [column expression file line namespace]} context]
    (when (@active-severity-predicate severity)
      (-write *logger*
              {::message        {:column    column
                                 :namespace namespace
                                 :result    result}
               ::severity       (str/upper-case (name severity))
               ::sourceLocation {:file     file
                                 :function expression
                                 :line     line}
               ::time            (Instant/now)}))
    result))

(defn make-log-macro
  [level]
  (let [expression `expression#
        result     `result#]
    `(defmacro ~(symbol (name level))
       [~expression]
       `(let [~result ~~expression]
          (log (assoc ~(meta ~'&form)
                      :expression ~~expression
                      :file       *file*
                      :namespace  (ns-name *ns*))
               ~~level ~result)))))

'(make-log-macro :emergency)

(defmacro debug
  [expression & more]
  `(let [result# ~expression]
     (log (assoc ~(meta &form)
                 :expression '~expression
                 :file       *file*
                 :namespace  (ns-name *ns*))
          :debug result# ~@more)))

(defmacro info
  [expression & more]
  `(let [result# ~expression]
     (log (assoc ~(meta &form)
                 :expression '~expression
                 :file       *file*
                 :namespace  (ns-name *ns*))
          :info result# ~@more)))

(defmacro notice
  [expression & more]
  `(let [result# ~expression]
     (log (assoc ~(meta &form)
                 :expression '~expression
                 :file       *file*
                 :namespace  (ns-name *ns*))
          :notice result# ~@more)))

(defmacro warning
  [expression & more]
  `(let [result# ~expression]
     (log (assoc ~(meta &form)
                 :expression '~expression
                 :file       *file*
                 :namespace  (ns-name *ns*))
          :warning result# ~@more)))

(defmacro error
  [expression & more]
  `(let [result# ~expression]
     (log (assoc ~(meta &form)
                 :expression '~expression
                 :file       *file*
                 :namespace  (ns-name *ns*))
          :error result# ~@more)))

(defmacro critical
  [expression & more]
  `(let [result# ~expression]
     (log (assoc ~(meta &form)
                 :expression '~expression
                 :file       *file*
                 :namespace  (ns-name *ns*))
          :critical result# ~@more)))

(defmacro alert
  [expression & more]
  `(let [result# ~expression]
     (log (assoc ~(meta &form)
                 :expression '~expression
                 :file       *file*
                 :namespace  (ns-name *ns*))
          :alert result# ~@more)))

(defmacro emergency
  [expression & more]
  `(let [result# ~expression]
     (log (assoc ~(meta &form)
                 :expression '~expression
                 :file       *file*
                 :namespace  (ns-name *ns*))
          :emergency result# ~@more)))

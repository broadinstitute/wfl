(ns wfl.tbl
  "Experiment with logs."
  (:require [clojure.data.json  :as json]
            [clojure.string :as str])
  (:import [java.time Instant]))

(alias 'lgc (ns-name (create-ns 'logging.googleapis.com)))

;; https://cloud.google.com/logging/docs/reference/v2/rest/v2/LogEntry#LogSeverity
;;
(def severities
  "The log severity keywords by increasing severity."
  [:debug :info :notice :warning :error :critical :alert :emergency])

(def ^:private severity?
  "The set of log severity keywords."
  (set severities))

(defn ^:private severity-string?
  "True when `severity` string names a log severity."
  [severity]
  (some severity? (-> severity str/lower-case keyword)))

(def active-map
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

(defn active-severity?
  "True when the `severity` keyword should be logged."
  [severity]
  (@active-severity-predicate severity))

;; https://cloud.google.com/logging/docs/agent/logging/configuration#special-fields
;;
(def google-fields
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
  (wfl.debug/trace [key value])
  ((:value-fn json/default-write-options)
   key
   (cond (keyword? value) (pr-str value)
         (list?    value) (pr-str value)
         (set?     value) (pr-str value)
         :else            value)))

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
      (-> edn
          (json/write-str :escape-slash false
                          :key-fn       key-fn
                          :value-fn     value-fn)
          println))))

(def ^:dynamic *logger*
  "The logger now."
  stdout-logger)

(defmacro log
  "Log `expression` with `severity` and `more` fields."
  [context severity expression more]
  `(let [context#   ~context #_(assoc (meta &form) :file *file*)
         result#    ~expression
         severity#  ~severity]
     (when (@active-severity-predicate severity#)
       (-write *logger*
               {::message        (merge {:column    (:column context#)
                                         :namespace '~(ns-name *ns*)
                                         :result    result#} ~more)
                ::severity       (str/upper-case (name severity#))
                ::sourceLocation {:file     (:file context#)
                                  :function ~expression
                                  :line     (:line context#)}
                ::time            (Instant/now)}))
     result#))

(defmacro make-log-macros
  []
  (let [binding '[expression & {:as more}]]
    `(do
       ~@(for [severity severities]
           (let [severity# severity]
             `(defmacro ~(symbol (name severity#)) ~binding
                (log (assoc (meta ~'&form) :file *file*)
                     ~severity# ~'expression ~'more)))))))


(eval (macroexpand '(make-log-macros)))

(info :fnord :foo "bar")

(error :fnord)
(error 'fnord)
(error (symbol "fnord"))
(macroexpand '(error :fnord))
(json/pprint (macroexpand '(error :fnord)))

(def two-three {:two 3})

(error (assoc two-three :three 4) :fnord (str "one" \: 23))
(error #{assoc two-three :three 4} :fnord (str "one" \: 23))
(error ['assoc two-three :three 4] :fnord (str "one" \: 23))
(error (list 'assoc two-three :three 4) :fnord (str "one" \: 23))
(error 'assoc :fnord (str "one" \: 23))

(def twenty-three 23)

(macroexpand '(error twenty-three))
(json/pprint (macroexpand '(error twenty-three)))

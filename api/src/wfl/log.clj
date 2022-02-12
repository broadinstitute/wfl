(ns wfl.log
  "Log to GCP Stackdriver."
  (:require [clojure.data.json  :as json]
            [clojure.spec.alpha :as s]
            [clojure.string     :as str]
            [clojure.template   :as template])
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

(s/def ::level-string   (s/and  string? level-string?))
(s/def ::level-request  (s/keys :req-un [::level-string]))
(s/def ::level-response (s/keys :req-un [::level-string]))

(def ^:private active-map
  "Map a level keyword to a set of active levels."
  (loop [sofar {} levels levels]
    (if-let [level (first levels)]
      (recur (assoc sofar level (set levels)) (rest levels))
      sofar)))

(def ^:private active-level-predicate
  "The active level predicate now."
  (atom (:error active-map)))

(defn set-active-level
  "Set `active-level-predicate` for the `level` string."
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

;; HACK: Override how JSONWriter handles some EDN values.
;; Not sure what to do about java.lang.Class and such.

(defn ^:private write-tagged
  "Write the TaggedLiteral X to OUT as JSON with OPTIONS."
  [x out options]
  (#'json/write-string (pr-str x) out
                       (assoc options
                              :escape-slash false
                              :key-fn       key-fn)))
(defn ^:private write-character
  "Write the Character X to OUT as JSON with OPTIONS."
  [x out options]
  (#'json/write-string (str x) out
                       (assoc options
                              :escape-slash false
                              :key-fn       key-fn)))
(defn ^:private write-class
  "Write the Class X to OUT as JSON with OPTIONS."
  [_x out options]
  (#'json/write-string "(-Some java.lang.Class!-)" out
                       (assoc options
                              :escape-slash false
                              :key-fn       key-fn)))
(defn ^:private write-throwable
  "Write the Throwable X to OUT as JSON with OPTIONS."
  [x out options]
  (#'json/write-object (Throwable->map x) out
                       (assoc options
                              :escape-slash false
                              :key-fn       key-fn)))
(extend clojure.lang.ExceptionInfo json/JSONWriter {:-write write-tagged})
(extend clojure.lang.TaggedLiteral json/JSONWriter {:-write write-tagged})
(extend java.lang.Character        json/JSONWriter {:-write write-character})
(extend java.lang.Class            json/JSONWriter {:-write write-class})
(extend java.lang.Object           json/JSONWriter {:-write write-character})
(extend java.lang.Throwable        json/JSONWriter {:-write write-throwable})

(defprotocol Logger
  "Log `edn` to `logger` as JSON."
  (-write [logger edn]))

(def disabled-logger
  "A logger that does not log."
  (reify Logger
    (-write [_ _])))

(def stdout-logger
  "A logger to write to standard output."
  (reify Logger
    (-write [_logger edn]
      (println (try (json/write-str edn
                                    :escape-slash false
                                    :key-fn       key-fn)
                    (catch Throwable t
                      (pr-str (str {:tried-to-log edn
                                    :cause        t}))))))))

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
                                 :function (pr-str expression)
                                 :line     line}
               ::time            (Instant/now)}))
    #_result))                          ; Not ready for this yet.

(defn ^:private make-one-macro
  "Define a log macro for `level`."
  [level]
  (template/apply-template
   '[macro level severity]
   '(defmacro macro [expression & more]
      `(let [result# ~expression]
         (log ~level (assoc ~(meta &form)
                            :expression '~expression
                            :file       ~*file*
                            :namespace  '~(ns-name *ns*))
              ~severity result# ~@more)))
   [(symbol (name level)) level (str/upper-case (name level))]))

(defmacro ^:private make-all-macros
  "Define a log macro for each level in `levels`."
  []
  `(do ~@(map make-one-macro levels)))

(make-all-macros)

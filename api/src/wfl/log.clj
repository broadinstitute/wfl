(ns wfl.log
  "Logging for WFL"
  (:require [clojure.data.json :as json]
            [clojure.string    :as str])
  (:import [java.time Instant]))

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

(defmacro log
  "Log `expression` with `severity`."
  [severity expression]
  (let [{:keys [line]} (meta &form)]
    `(let [x# ~expression]
       (-write *logger*
               {:timestamp (Instant/now)
                :severity ~(-> severity name str/upper-case)
                :logging.googleapis.com/jsonPayload
                {'~expression x#}
                :logging.googleapis.com/sourceLocation
                {:file ~*file* :line ~line}})
       x#)))

(defmacro debug
  "Log `expression` for debugging."
  [expression]
  `(log :debug ~expression))

(defmacro error
  "Log `expression` as an error."
  [expression]
  `(log :error ~expression))

(defmacro info
  "Log `expression` as information."
  [expression]
  `(log :info ~expression))

(defmacro warn
  "Log `expression` as a warning."
  [expression]
  `(log :warning ~expression))

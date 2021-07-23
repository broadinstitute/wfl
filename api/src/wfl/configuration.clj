(ns wfl.configuration
  "Configuration for WFL. A configuration table exists as a simple key
   value store for simple configuration."
  (:require [clojure.string                 :as str]
            [wfl.jdbc                       :as jdbc]))

(def ^:private configuration-table "configuration")

(defn load-logging-level
  "Use transaction `tx` to load the current logging level of the application."
  [tx]
  (let [levels (jdbc/query tx ["SELECT value FROM configuration WHERE key = 'LOGGING_LEVEL' LIMIT 1"])]
    (if (empty? levels)
      (-> :info name str/upper-case)
      (str/upper-case (get (first levels) :value)))))

(defn upsert-config
  "Use transaction `tx` to insert or update a configuration row."
  [tx key value]
  (letfn [(get-row [] (jdbc/query tx [(str "SELECT * FROM configuration WHERE key = '" key "' LIMIT 1")]))]
    (if (empty? (get-row))
      (if (empty? (jdbc/insert! tx configuration-table {:key key :value value}))
        (throw (ex-info "Unable to insert " key))
        (get-row))
      (if (empty? (jdbc/update! tx configuration-table {:value value} ["key = ?" key]))
        (throw (ex-info "Unable to update " key))
        (get-row)))))

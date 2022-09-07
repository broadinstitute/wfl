(ns wfl.configuration
  "A simple key value store to configure WFL."
  (:require [clojure.string :as str]
            [wfl.jdbc       :as jdbc]))

(def ^:private configuration-table "configuration")

(defn get-config
  "Use transaction `tx` to load the current logging level of the application."
  [tx key]
  (let [configs (jdbc/query tx ["SELECT value FROM configuration WHERE key = ?" key])]
    (if (empty? configs)
      (-> :info name str/upper-case)
      (str/upper-case (get (first configs) :value)))))

(defn upsert-config
  "Use transaction `tx` to insert or update a configuration row."
  [tx key value]
  (letfn [(get-row [] (jdbc/query tx ["SELECT * FROM configuration WHERE key = ?" key]))]
    (if (empty? (get-row))
      (jdbc/insert! tx configuration-table {:key key :value value})
      (if (empty? (jdbc/update! tx configuration-table {:value value} ["key = ?" key]))
        (throw (ex-info "Unable to update " key))
        (get-row)))))

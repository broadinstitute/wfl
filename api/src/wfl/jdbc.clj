(ns wfl.jdbc
  "clojure.tools.logging wrapping for clojure.java.jdbc"
  (:require [clojure.java.jdbc :as jdbc]
            [clojure.tools.logging.readable :as log]))

(def ^:private remember-db
  "Memoized helper to only print the db out the first time"
  (memoize
   #(let [identifier (str (:user %) "@" (:connection-uri %) "#" (rand-int 10000))]
      (log/info "JDBC new database configuration" identifier)
      (log/info (dissoc % :password))
      identifier)))

(defn ^:private format-db
  "Return the db config's identifier, printing the whole db if it is new."
  [db]
  (wfl.jdbc/remember-db (into {} (filter #(-> % last string?) db))))

(defn query
  "Reader for [[clojure.java.jdbc/query]]"
  [& arguments]
  (fn [db]
    (log/info "JDBC query:" (format-db db) arguments)
    (apply jdbc/query db arguments)))

(defn update!
  "Reader for [[clojure.java.jdbc/update!]]"
  [& arguments]
  (fn [db]
    (log/info "JDBC update!:" (format-db db) arguments)
    (apply jdbc/update! db arguments)))

(defn insert-multi!
  "Reader for [[clojure.java.jdbc/insert-multi!]]"
  [& arguments]
  (fn [db]
    (log/info "JDBC insert-rows!:" (format-db db) arguments)
    (apply jdbc/insert-multi! db arguments)))

(defn execute!
  "Reader for [[clojure.java.jdbc/execute!]]"
  [& arguments]
  (fn [db]
    (log/info "JDBC execute!:" (format-db db) arguments)
    (apply jdbc/execute! db arguments)))

(defn db-do-commands
  "Reader for [[clojure.java.jdbc/db-do-commands]]"
  [& arguments]
  (fn [db]
    (log/info "JDBC db-do-commands:" (format-db db) arguments)
    (apply jdbc/db-do-commands db arguments)))

(defn insert!
  "Reader for [[clojure.java.jdbc/insert!]]"
  [& arguments]
  (fn [db]
    (log/info "JDBC insert-rows!:" (format-db db) arguments)
    (apply jdbc/insert! db arguments)))

(defmacro prepare-statement
  "Reader for [[clojure.java.jdbc/prepare-statement]].
   Does not log since the statement would be used later."
  [& arguments]
  (fn [db]
    (apply jdbc/prepare-statement db arguments)))

(defn run-transaction!
  "Execute the `reader` in the context of a database transaction."
  [db-config reader]
  (let [id (rand-int 10000)]
    (log/info "JDBC transaction" id "started to" (format-db db-config))
    (let [result (jdbc/with-db-transaction [tx db-config]
                   (reader tx))]
      (log/info "JDBC SQL transaction" id "ended")
      result)))

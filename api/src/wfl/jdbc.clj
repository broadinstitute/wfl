(ns wfl.jdbc
  "wfl.log wrapping for clojure.java.jdbc"
  (:require [clojure.java.jdbc :as jdbc]
            [clojure.string    :as str]
            [wfl.log           :as log])
  (:import (clojure.lang IPersistentVector)
           (java.sql PreparedStatement Array)))

(def ^:private remember-db
  "Memoized helper to only print the db out the first time"
  (memoize (fn [{:keys [connection-uri user] :as db}]
             (let [random     (rand-int 10000)
                   identifier (str user "@" connection-uri "#" random)]
               (log/info (dissoc db :password))
               identifier))))

(defn format-db
  "Return the db config's identifier, printing the whole db if it is new."
  [db]
  (wfl.jdbc/remember-db (into {} (filter #(-> % last string?) db))))

(defn query
  "Logged alias for `clojure.java.jdbc/query`."
  ([db sql-params opts]
   (log/debug
    (str/join \space ["jdbc/query:" (format-db db) (pr-str sql-params) opts]))
   (jdbc/query db sql-params opts))
  ([db sql-params]
   (query db sql-params {})))

(defn update!
  "Logged alias for `clojure.java.jdbc/update!`."
  ([db table set-map where-clause opts]
   (log/info
    (str/join \space
              ["jdbc/update!" (format-db db) table set-map where-clause opts]))
   (jdbc/update! db table set-map where-clause opts))
  ([db table set-map where-clause]
   (update! db table set-map where-clause {})))

(defn insert-multi!
  "Logged alias for `clojure.java.jdbc/insert-multi!`."
  ([db table cols values opts]
   (log/info
    (str/join \space ["jdbc/insert-multi!" (format-db db)
                      table cols values opts]))
   (jdbc/insert-multi! db table cols values opts))
  ([db table cols-or-rows values-or-opts]
   (log/info
    (str/join \space ["jdbc/insert-multi!" (format-db db)
                      table cols-or-rows values-or-opts]))
   (jdbc/insert-multi! db table cols-or-rows values-or-opts))
  ([db table rows]
   (log/info
    (str/join \space ["jdbc/insert-multi!" (format-db db)
                      table rows]))
   (jdbc/insert-multi! db table rows)))

(defn execute!
  "Logged alias for [[clojure.java.jdbc/execute!]]"
  ([db sql-params opts]
   (log/info
    (str/join \space
              ["jdbc/execute!" (format-db db) (pr-str sql-params) opts]))
   (jdbc/execute! db sql-params opts))
  ([db sql-params]
   (execute! db sql-params {})))

(defn db-do-commands
  "Logged alias for `clojure.java.jdbc/db-do-commands`."
  ([db sql-commands]
   (log/info
    (str/join \space ["jdbc/db-do-commands" (format-db db)
                      sql-commands]))
   (jdbc/db-do-commands db sql-commands))
  ([db transaction? sql-commands]
   (log/info
    (str/join \space ["jdbc/db-do-commands" (format-db db)
                      transaction? sql-commands]))
   (jdbc/db-do-commands db transaction? sql-commands)))

(defn insert!
  "Logged alias for `clojure.java.jdbc/insert!`."
  ([db table row]
   (log/info
    (str/join \space ["jdbc/insert" (format-db db)
                      table row]))
   (jdbc/insert! db table row))
  ([db table cols-or-row values-or-opts]
   (log/info
    (str/join \space ["jdbc/insert" (format-db db)
                      table cols-or-row values-or-opts]))
   (jdbc/insert! db table cols-or-row values-or-opts))
  ([db table cols values opts]
   (log/info
    (str/join \space ["jdbc/insert" (format-db db)
                      table cols values opts]))
   (jdbc/insert! db table cols values opts)))

(defmacro with-db-transaction
  "Alias `clojure.java.jdbc/with-db-transaction` for consistency."
  [& body]
  `(jdbc/with-db-transaction ~@body))

(defmacro prepare-statement
  "Alias `clojure.java.jdbc/prepare-statement` for consistency."
  [& body]
  `(jdbc/prepare-statement ~@body))

(defn get-connection
  "Logged alias for `clojure.java.jdbc/get-connection`."
  ([db-spec opts]
   (log/info
    (str/join \space
              ["JBDC SQL connection made:" (format-db db-spec) opts]))
   (jdbc/get-connection db-spec opts))
  ([db-spec]
   (get-connection db-spec {})))

;; Expertly copied and pasted from Stack Overflow:
;; https://stackoverflow.com/a/25786990
(extend-protocol clojure.java.jdbc/ISQLParameter
  IPersistentVector
  (set-parameter [v ^PreparedStatement stmt ^long i]
    (let [conn          (.getConnection stmt)
          meta          (.getParameterMetaData stmt)
          [head & rest] (.getParameterTypeName meta i)]
      (if-let [elem-type (when (= head \_) (str/join rest))]
        (.setObject stmt i (.createArrayOf conn elem-type (to-array v)))
        (.setObject stmt i v)))))

(extend-protocol clojure.java.jdbc/IResultSetReadColumn
  Array
  (result-set-read-column [val _ _]
    (vec (.getArray val))))

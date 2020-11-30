(ns wfl.jdbc
  "clojure.tools.logging wrapping for clojure.java.jdbc"
  (:require [clojure.java.jdbc :as jdbc]
            [clojure.tools.logging.readable :as log]))

(def ^:private remember-db
  "Memoized helper to only print the db out the first time"
  (memoize (fn [db]
             (let [random (rand-int 10000)
                   identifier (str (:user db) "@" (:connection-uri db) "#" random)]
               (log/infof "JDBC new DB config %s observed (the #%s isn't important, just unique):"
                          identifier random)
               (log/info (dissoc db :password))
               identifier))))

(defn format-db
  "Return the db config's identifier, printing the whole db if it is new."
  [db]
  (wfl.jdbc/remember-db (into {} (filter #(-> % last string?) db))))

(defmacro query
  "Logged alias for [[clojure.java.jdbc/query]]"
  ([db sql-params]
   `(do
      (log/trace "JDBC SQL query (without opts):" (format-db ~db) ~sql-params)
      (jdbc/query ~db ~sql-params)))
  ([db sql-params opts]
   `(do
      (log/trace "JDBC SQL query:" (format-db ~db) ~sql-params ~opts)
      (jdbc/query ~db ~sql-params ~opts))))

(defmacro update!
  "Logged alias for [[clojure.java.jdbc/update!]]"
  ([db table set-map where-clause]
   `(do
      (log/info "JDBC SQL update! (without opts):"
                (format-db ~db) ~table ~set-map ~where-clause)
      (jdbc/update! ~db ~table ~set-map ~where-clause)))
  ([db table set-map where-clause opts]
   `(do
      (log/info "JDBC SQL update!:"
                (format-db ~db) ~table ~set-map ~where-clause ~opts)
      (jdbc/update! ~db ~table ~set-map ~where-clause ~opts))))

(defmacro insert-multi!
  "Logged alias for [[clojure.java.jdbc/insert-multi!]]"
  ([db table rows]
   `(do
      (log/info "JDBC SQL insert-rows! (without opts):"
                (format-db ~db) ~table ~rows)
      (jdbc/insert-multi! ~db ~table ~rows)))
  ([db table cols-or-rows values-or-opts]
   `(do
      (if (map? values-or-opts)
        (log/info "JDBC SQL insert-rows!:"
                  (format-db ~db) ~table ~cols-or-rows ~values-or-opts)
        (log/info "JDBC SQL insert-cols! (without opts):"
                  (format-db ~db) ~table ~cols-or-rows ~values-or-opts))
      (jdbc/insert-multi! ~db ~table ~cols-or-rows ~values-or-opts)))
  ([db table cols values opts]
   `(do
      (log/info "JDBC SQL insert-cols!:"
                (format-db ~db) ~table ~cols ~values ~opts)
      (jdbc/insert-multi! ~db ~table ~cols ~values ~opts))))

(defmacro execute!
  "Logged alias for [[clojure.java.jdbc/execute!]]"
  ([db sql-params]
   `(do
      (log/info "JDBC SQL execute! (without opts):" (format-db ~db) ~sql-params)
      (jdbc/execute! ~db ~sql-params)))
  ([db sql-params opts]
   `(do
      (log/info "JDBC SQL execute!:" (format-db ~db) ~sql-params ~opts)
      (jdbc/execute! ~db ~sql-params ~opts))))

(defmacro db-do-commands
  "Logged alias for [[clojure.java.jdbc/db-do-commands]]"
  ([db sql-commands]
   `(do
      (log/info "JDBC SQL db-do-commands:" (format-db ~db) ~sql-commands)
      (jdbc/db-do-commands ~db ~sql-commands)))
  ([db transaction? sql-commands]
   `(do
      (log/info "JDBC SQL db-do-commands:"
                (format-db ~db) ~transaction? ~sql-commands)
      (jdbc/db-do-commands ~db ~transaction? ~sql-commands))))

(defmacro insert!
  "Logged alias for [[clojure.java.jdbc/insert!]]"
  ([db table row]
   `(do
      (log/info "JDBC SQL insert-rows! (without opts):"
                (format-db ~db) ~table [~row])
      (jdbc/insert! ~db ~table ~row)))
  ([db table cols-or-row values-or-opts]
   `(do
      (if (map? values-or-opts)
        (log/info "JDBC SQL insert-rows!:"
                  (format-db ~db) ~table [~cols-or-row] ~values-or-opts)
        (log/info "JDBC SQL insert-cols! (without opts):"
                  (format-db ~db) ~table ~cols-or-row [~values-or-opts]))
      (jdbc/insert! ~db ~table ~cols-or-row ~values-or-opts)))
  ([db table cols values opts]
   `(do
      (log/info "JDBC SQL insert-cols!:"
                (format-db ~db) ~table ~cols [~values] ~opts)
      (jdbc/insert! ~db ~table ~cols ~values ~opts))))

(defmacro with-db-transaction
  "Logged alias for [[clojure.java.jdbc/with-db-transaction]]"
  [binding & body]
  `(let [id# (rand-int 10000)]
     (log/info "JDBC SQL transaction" id# "started to"
               (format-db ~(second binding)))
     (let [exe# (jdbc/with-db-transaction ~binding ~@body)]
       (log/info "JDBC SQL transaction" id# "ended")
       exe#)))

(defmacro prepare-statement
  "Alias for [[clojure.java.jdbc/prepare-statement]].
  Does not log since the statement would be used later."
  ([con sql]
   `(jdbc/prepare-statement ~con ~sql))
  ([^java.sql.Connection con ^String sql opts]
   `(jdbc/prepare-statement ~con ~sql ~opts)))

(defmacro get-connection
  "Logged alias for [[clojure.java.jdbc/prepare-statement]]"
  ([db-spec]
   `(do
      (log/info "JBDC SQL connection made (no opts):" (format-db ~db-spec))
      (jdbc/get-connection ~db-spec)))
  ([db-spec opts]
   `(do
      (log/info "JBDC SQL connection made:" (format-db ~db-spec) ~opts)
      (jdbc/get-connection ~db-spec ~opts))))

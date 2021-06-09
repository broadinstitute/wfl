(ns wfl.jdbc
  "clojure.tools.logging wrapping for clojure.java.jdbc"
  (:require [clojure.java.jdbc              :as jdbc]
            [clojure.tools.logging.readable :as log])
  (:import (clojure.lang IPersistentVector)
           (java.sql PreparedStatement Array)))

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
   `(let [db#         ~db
          sql-params# ~sql-params]
      (log/trace "jdbc/query:" (format-db db#) sql-params#)
      (jdbc/query db# sql-params#)))
  ([db sql-params opts]
   `(let [db#         ~db
          sql-params# ~sql-params
          opts#       ~opts]
      (log/trace "jdbc/query:" (format-db db#) sql-params# opts#)
      (jdbc/query db# sql-params# opts#))))

(defmacro update!
  "Logged alias for [[clojure.java.jdbc/update!]]"
  ([db table set-map where-clause]
   `(let [db#           ~db
          table#        ~table
          set-map#      ~set-map
          where-clause# ~where-clause]
      (log/info "jdbc/update!" (format-db db#) table# set-map# where-clause#)
      (jdbc/update! db# table# set-map# where-clause#)))
  ([db table set-map where-clause opts]
   `(let [db#           ~db
          table#        ~table
          set-map#      ~set-map
          where-clause# ~where-clause
          opts#         ~opts]
      (log/info "jdbc/update!" (format-db db#) table# set-map# where-clause# opts#)
      (jdbc/update! db# table# set-map# where-clause# opts#))))

(defmacro insert-multi!
  "Logged alias for [[clojure.java.jdbc/insert-multi!]]"
  ([db table rows]
   `(let [db#    ~db
          table# ~table
          rows#  ~rows]
      (log/info "jdbc/insert-multi!" (format-db db#) table# rows#)
      (jdbc/insert-multi! db# table# rows#)))
  ([db table cols-or-rows values-or-opts]
   `(let [db#             ~db
          table#          ~table
          cols-or-rows#   ~cols-or-rows
          values-or-opts# ~values-or-opts]
      (log/info "jdbc/insert-multi!" (format-db db#) table# cols-or-rows# values-or-opts#)
      (jdbc/insert-multi! db# table# cols-or-rows# values-or-opts#)))
  ([db table cols values opts]
   `(let [db#     ~db
          table#  ~table
          cols#   ~cols
          values# ~values
          opts#   ~opts]
      (log/info "jdbc/insert-multi!" (format-db db#) table# cols# values# opts#)
      (jdbc/insert-multi! db# table# cols# values# opts#))))

(defmacro execute!
  "Logged alias for [[clojure.java.jdbc/execute!]]"
  ([db sql-params]
   `(let [db#         ~db
          sql-params# ~sql-params]
      (log/info "jdbc/execute!" (format-db db#) sql-params#)
      (jdbc/execute! db# sql-params#)))
  ([db sql-params opts]
   `(let [db#         ~db
          sql-params# ~sql-params
          opts#       ~opts]
      (log/info "jdbc/execute!" (format-db db#) sql-params# opts#)
      (jdbc/execute! db# sql-params# opts#))))

(defmacro db-do-commands
  "Logged alias for [[clojure.java.jdbc/db-do-commands]]"
  ([db sql-commands]
   `(let [db#           ~db
          sql-commands# ~sql-commands]
      (log/info "jbs/db-do-commands" (format-db db#) sql-commands#)
      (jdbc/db-do-commands db# sql-commands#)))
  ([db transaction? sql-commands]
   `(let [db#           ~db
          transaction?# ~transaction?
          sql-commands# ~sql-commands]
      (log/info "jbs/db-do-commands" (format-db db#) transaction?# sql-commands#)
      (jdbc/db-do-commands db# transaction?# sql-commands#))))

(defmacro insert!
  "Logged alias for [[clojure.java.jdbc/insert!]]"
  ([db table row]
   `(let [db#    ~db
          table# ~table
          row#   ~row]
      (log/info "jdbc/insert" (format-db db#) table# row#)
      (jdbc/insert! db# table# row#)))
  ([db table cols-or-row values-or-opts]
   `(let [db#             ~db
          table#          ~table
          cols-or-row#    ~cols-or-row
          values-or-opts# ~values-or-opts]
      (log/info "jdbc/insert" (format-db db#) table# cols-or-row# values-or-opts#)
      (jdbc/insert! db# table# cols-or-row# values-or-opts#)))
  ([db table cols values opts]
   `(let [db#     ~db
          table#  ~table
          cols#   ~cols
          values# ~values]
      (log/info "jdbc/insert" (format-db db#) table# cols# values# opts)
      "jdbc/insert" db# table# cols# values# opts)))

(defmacro with-db-transaction
  "Logged alias for [[clojure.java.jdbc/with-db-transaction]]"
  [binding & body]
  `(let [id#    (rand-int 10000)
         init# ~(second binding)]
     (log/info "JDBC transaction" id# "started to" (format-db init#))
     (let [exe# (jdbc/with-db-transaction [~(first binding) init#] ~@body)]
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

;; Expertly copied and pasted from Stack Overflow:
;; https://stackoverflow.com/a/25786990
(extend-protocol clojure.java.jdbc/ISQLParameter
  IPersistentVector
  (set-parameter [v ^PreparedStatement stmt ^long i]
    (let [conn          (.getConnection stmt)
          meta          (.getParameterMetaData stmt)
          [head & rest] (.getParameterTypeName meta i)]
      (if-let [elem-type (when (= head \_) (apply str rest))]
        (.setObject stmt i (.createArrayOf conn elem-type (to-array v)))
        (.setObject stmt i v)))))

(extend-protocol clojure.java.jdbc/IResultSetReadColumn
  Array
  (result-set-read-column [val _ _]
    (into [] (.getArray val))))

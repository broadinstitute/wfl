(ns wfl.jdbc
  "wfl.log wrapping for clojure.java.jdbc"
  (:require [clojure.java.jdbc :as jdbc]
            [clojure.string    :as str]
            [wfl.log           :as logger])
  (:import (clojure.lang IPersistentVector)
           (java.sql PreparedStatement Array)))

(def ^:private remember-db
  "Memoized helper to only print the db out the first time"
  (memoize (fn [{:keys [connection-uri user] :as db}]
             (let [random     (rand-int 10000)
                   identifier (str user "@" connection-uri "#" random)]
               (logger/info (dissoc db :password))
               identifier))))

(defn format-db
  "Return the db config's identifier, printing the whole db if it is new."
  [db]
  (wfl.jdbc/remember-db (into {} (filter #(-> % last string?) db))))

(defmacro query
  "Logged alias for [[clojure.java.jdbc/query]]"
  ([db sql-params]
   (let [{:keys [line]} (meta &form)]
     `(let [db#         ~db
            sql-params# ~sql-params]
        (logger/debug (str/join " " ["jdbc/query:" (format-db db#) sql-params#]) ~line)
        (jdbc/query db# sql-params#))))
  ([db sql-params opts]
   (let [{:keys [line]} (meta &form)]
     `(let [db#         ~db
            sql-params# ~sql-params
            opts#       ~opts]
        (logger/debug (str/join " " ["jdbc/query:" (format-db db#) sql-params# opts#]) ~line)
        (jdbc/query db# sql-params# opts#)))))

(defmacro update!
  "Logged alias for [[clojure.java.jdbc/update!]]"
  ([db table set-map where-clause]
   (let [{:keys [line]} (meta &form)]
     `(let [db#           ~db
            table#        ~table
            set-map#      ~set-map
            where-clause# ~where-clause]
        (logger/info (str/join " " ["jdbc/update!" (format-db db#) table# set-map# where-clause#]) ~line)
        (jdbc/update! db# table# set-map# where-clause#))))
  ([db table set-map where-clause opts]
   (let [{:keys [line]} (meta &form)]
     `(let [db#           ~db
            table#        ~table
            set-map#      ~set-map
            where-clause# ~where-clause
            opts#         ~opts]
        (logger/info (str/join " " ["jdbc/update!" (format-db db#) table# set-map# where-clause# opts#]) ~line)
        (jdbc/update! db# table# set-map# where-clause# opts#)))))

(defmacro insert-multi!
  "Logged alias for [[clojure.java.jdbc/insert-multi!]]"
  ([db table rows]
   (let [{:keys [line]} (meta &form)]
     `(let [db#    ~db
            table# ~table
            rows#  ~rows]
        (logger/info (str/join " " ["jdbc/insert-multi!" (format-db db#) table# rows#]) ~line)
        (jdbc/insert-multi! db# table# rows#))))
  ([db table cols-or-rows values-or-opts]
   (let [{:keys [line]} (meta &form)]
     `(let [db#             ~db
            table#          ~table
            cols-or-rows#   ~cols-or-rows
            values-or-opts# ~values-or-opts]
        (logger/info (str/join " " ["jdbc/insert-multi!" (format-db db#) table# cols-or-rows# values-or-opts#]) ~line)
        (jdbc/insert-multi! db# table# cols-or-rows# values-or-opts#))))
  ([db table cols values opts]
   (let [{:keys [line]} (meta &form)]
     `(let [db#     ~db
            table#  ~table
            cols#   ~cols
            values# ~values
            opts#   ~opts]
        (logger/info (str/join " " ["jdbc/insert-multi!" (format-db db#) table# cols# values# opts#]) ~line)
        (jdbc/insert-multi! db# table# cols# values# opts#)))))

(defmacro execute!
  "Logged alias for [[clojure.java.jdbc/execute!]]"
  ([db sql-params]
   (let [{:keys [line]} (meta &form)]
     `(let [db#         ~db
            sql-params# ~sql-params]
        (logger/info (str/join " " ["jdbc/execute!" (format-db db#) sql-params#]) ~line)
        (jdbc/execute! db# sql-params#))))
  ([db sql-params opts]
   (let [{:keys [line]} (meta &form)]
     `(let [db#         ~db
            sql-params# ~sql-params
            opts#       ~opts]
        (logger/info (str/join " " ["jdbc/execute!" (format-db db#) sql-params# opts#]) ~line)
        (jdbc/execute! db# sql-params# opts#)))))

(defmacro db-do-commands
  "Logged alias for [[clojure.java.jdbc/db-do-commands]]"
  ([db sql-commands]
   (let [{:keys [line]} (meta &form)]
     `(let [db#           ~db
            sql-commands# ~sql-commands]
        (logger/info (str/join " " ["jbs/db-do-commands" (format-db db#) sql-commands#]) ~line)
        (jdbc/db-do-commands db# sql-commands#))))
  ([db transaction? sql-commands]
   (let [{:keys [line]} (meta &form)]
     `(let [db#           ~db
            transaction?# ~transaction?
            sql-commands# ~sql-commands]
        (logger/info (str/join " " ["jbs/db-do-commands" (format-db db#) transaction?# sql-commands#]) ~line)
        (jdbc/db-do-commands db# transaction?# sql-commands#)))))

(defmacro insert!
  "Logged alias for [[clojure.java.jdbc/insert!]]"
  ([db table row]
   (let [{:keys [line]} (meta &form)]
     `(let [db#    ~db
            table# ~table
            row#   ~row]
        (logger/info (str/join " " ["jdbc/insert" (format-db db#) table# row#]) ~line)
        (jdbc/insert! db# table# row#))))
  ([db table cols-or-row values-or-opts]
   (let [{:keys [line]} (meta &form)]
     `(let [db#             ~db
            table#          ~table
            cols-or-row#    ~cols-or-row
            values-or-opts# ~values-or-opts]
        (logger/info (str/join " " ["jdbc/insert" (format-db db#) table# cols-or-row# values-or-opts#]) ~line)
        (jdbc/insert! db# table# cols-or-row# values-or-opts#))))
  ([db table cols values opts]
   (let [{:keys [line]} (meta &form)]
     `(let [db#     ~db
            table#  ~table
            cols#   ~cols
            values# ~values
            opts#   ~opts]
        (logger/info (str/join " " ["jdbc/insert" (format-db db#) table# cols# values# opts#]) ~line)
        "jdbc/insert" db# table# cols# values# opts#))))

(defmacro with-db-transaction
  "Logged alias for [[clojure.java.jdbc/with-db-transaction]]"
  [binding & body]
  (let [{:keys [line]} (meta &form)]
    `(let [id#    (rand-int 10000)
           init# ~(second binding)]
       (logger/info (str/join " " ["JDBC transaction" id# "started to" (format-db init#)]) ~line)
       (let [exe# (jdbc/with-db-transaction [~(first binding) init#] ~@body)]
         (logger/info (str/join " " ["JDBC SQL transaction" id# "ended"]) ~line)
         exe#))))

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
   (let [{:keys [line]} (meta &form)]
     `(do
        (logger/info (str/join " " ["JBDC SQL connection made (no opts):" (format-db ~db-spec)]) ~line)
        (jdbc/get-connection ~db-spec))))
  ([db-spec opts]
   (let [{:keys [line]} (meta &form)]
     `(do
        (logger/info (str/join " " ["JBDC SQL connection made:" (format-db ~db-spec) ~opts]) ~line)
        (jdbc/get-connection ~db-spec ~opts)))))

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

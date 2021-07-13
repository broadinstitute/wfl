(ns wfl.service.postgres
  "Talk to the Postgres database."
  (:require [clojure.string        :as str]
            [wfl.environment       :as env]
            [wfl.jdbc              :as jdbc]))

(def ^:private testing-db-overrides
  "Override the configuration used by `wfl-db-config` for testing. Use
  `wfl.tools.fixtures/temporary-postgresql-database` instead of this."
  (atom {}))

(defn wfl-db-config
  "Get the database configuration."
  []
  (-> {:classname       "org.postgresql.Driver"
       :db-name         "wfl"
       :instance-name   "zero-postgresql"
       ;; https://www.postgresql.org/docs/9.1/transaction-iso.html
       :isolation-level :serializable
       :subprotocol     "postgresql"}
      (assoc :connection-uri (env/getenv "WFL_POSTGRES_URL")
             :password (env/getenv "WFL_POSTGRES_PASSWORD")
             :user (or (env/getenv "WFL_POSTGRES_USERNAME") (env/getenv "USER") "postgres"))
      (merge @testing-db-overrides)))

(defn table-exists?
  "Check if TABLE exists using transaction TX."
  [tx table]
  (->> (name table)
       str/lower-case
       (conj ["SELECT * FROM INFORMATION_SCHEMA.TABLES WHERE TABLE_NAME = ?"])
       (jdbc/query tx)
       (count)
       (not= 0)))

(defn throw-unless-table-exists
  [tx table-name]
  (when-not (and table-name (table-exists? tx table-name))
    (throw (ex-info "Table not found" {:table table-name}))))

(defn get-table
  "Return TABLE using transaction TX sorted by row id."
  [tx table]
  (throw-unless-table-exists tx table)
  (jdbc/query tx (format "SELECT * FROM %s ORDER BY id ASC" table)))

(defn table-length
  "Use `tx` to return the number of records in `table-name`."
  [tx table-name]
  (throw-unless-table-exists tx table-name)
  (->> (format "SELECT COUNT(*) FROM %s" table-name)
       (jdbc/query tx)
       first
       :count))

(defn table-max
  "Use `tx` to return the maximum value of `column` in `table-name`."
  [tx table-name column]
  (throw-unless-table-exists tx table-name)
  (-> (format "SELECT MAX(%s) FROM %s" (name column) (name table-name))
      (->> (jdbc/query tx))
      first
      :max
      (or 0)))

(defn load-record-by-id! [tx table id]
  (let [query        "SELECT * FROM %s WHERE id = ? LIMIT 1"
        [record & _] (jdbc/query tx [(format query table) id])]
    (when-not record
      (throw (ex-info (str "No such record") {:id id :table table})))
    record))

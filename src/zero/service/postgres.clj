(ns zero.service.postgres
  "Talk to the Postgres database."
  (:require [clojure.data.json :as json]
            [clojure.java.io :as io]
            [clojure.java.jdbc :as jdbc]
            [clojure.string :as str]
            [clj-http.client :as http]
            [zero.environments :as env]
            [zero.once :as once]
            [zero.util :as util]
            [zero.zero :as zero])
  (:import [java.time OffsetDateTime]
           [liquibase.integration.commandline Main]))

(defn zero-db-url
  "An URL for the zero-postgresql service in ENVIRONMENT."
  [environment]
  (let [project (get-in env/stuff [environment :server :project])
        wfl (when project
              (letfn [(postgresql? [{:keys [instanceType name region]}]
                        (when (= [instanceType         name]
                                 ["CLOUD_SQL_INSTANCE" "zero-postgresql"])
                          (str "//google/wfl?useSSL=false"
                               "&socketFactory="
                               "com.google.cloud.sql.postgres.SocketFactory"
                               "&cloudSqlInstance="
                               (str/join ":" [project region name]))))]
                (-> {:method :get
                     :url (str/join
                            "/" ["https://www.googleapis.com/sql/v1beta4"
                                 "projects" project "instances"])
                     :headers (merge {"Content-Type" "application/json"}
                                     (once/get-local-auth-header))}
                    http/request :body
                    (json/read-str :key-fn keyword) :items
                    (->> (keep postgresql?))
                    first)))]
    (str/join ":" ["jdbc" "postgresql" (or wfl "wfl")])))

(defn zero-db-config
  "Get the config for the zero database in ENVIRONMENT."
  [environment]
  (let [{:strs [ZERO_POSTGRES_PASSWORD
                ZERO_POSTGRES_URL
                ZERO_POSTGRES_USERNAME]}
        (util/getenv)
        [password url user]
        (if ZERO_POSTGRES_URL
          [ZERO_POSTGRES_PASSWORD ZERO_POSTGRES_URL ZERO_POSTGRES_USERNAME]
          ["password" "jdbc:postgresql:wfl" (util/getenv "USER" "postgres")])]
    (assoc {:instance-name "zero-postgresql"
            :db-name       "wfl"
            :classname     "org.postgresql.Driver"
            :subprotocol   "postgresql"
            :vault         "secret/dsde/gotc/dev/zero"}
           :connection-uri url
           :password       password
           :user           user)))

(defn query
  "Query the database in ENVIRONMENT with SQL."
  [environment sql]
  (jdbc/query (zero-db-config environment) sql))

(defn insert!
  "Add ROW map to TABLE in the database in ENVIRONMENT."
  [environment table row]
  (jdbc/insert! (zero-db-config environment) table row))

(defn run-liquibase-update
  "Run Liquibase update on the database at URL with USERNAME and PASSWORD."
  [url username password]
  (let [status (Main/run
                 (into-array
                   String
                   [(str "--url=" url)
                    (str "--changeLogFile=database/changelog.xml")
                    (str "--username=" username)
                    (str "--password=" password)
                    "update"]))]
    (when-not (zero? status)
      (throw
        (Exception.
          (format "Liquibase failed with: %s" status))))))

(defn run-liquibase
  "Migrate the database schema for ENV using Liquibase."
  ([env]
   (let [{:keys [username password]} (-> env/stuff env :server :vault
                                         util/vault-secrets)]
     (run-liquibase-update (zero-db-url env) username password)))
  ([]
   (run-liquibase-update (zero-db-url :debug)
                         (util/getenv "USER" "postgres")
                         "password")))

(defn get-table
  "Return TABLE using transaction TX."
  [tx table]
  (jdbc/query tx (format "SELECT * FROM %s" table)))

;; HACK: We don't have the workload environment here.
;;
(defn cromwell-status
  "NIL or the status of the workflow with UUID on CROMWELL."
  [cromwell uuid]
  (-> {:method  :get                    ; :debug true :debug-body true
       :url     (str/join "/" [cromwell "api" "workflows" "v1" uuid "status"])
       :headers (once/get-local-auth-header)}
      http/request :body
      (json/read-str :key-fn keyword)
      :status util/do-or-nil))

(defn update-workflow-status!
  "Use TX to update the status of WORKFLOW in ITEMS table."
  [tx cromwell items {:keys [id uuid] :as _workflow}]
  (letfn [(maybe [m k v] (if v (assoc m k v) m))]
    (when uuid
      (let [now    (OffsetDateTime/now)
            status (cromwell-status cromwell uuid)]
        (jdbc/update! tx items
                      (maybe {:updated now :uuid uuid} :status status)
                      ["id = ?" id])))))

(defn update-workload!
  "Use transaction TX to update workload ITEMS statuses from CROMWELL."
  [tx cromwell items]
  (->> items
       (get-table tx)
       (run! (partial update-workflow-status! tx cromwell items))))

(defn get-workload-for-uuid
  "Use transaction TX to return workload with UUID."
  [tx {:keys [uuid]}]
  (letfn [(unnilify [m] (into {} (filter second m)))]
    (let [select   ["SELECT * FROM workload WHERE uuid = ?" uuid]
          {:keys [cromwell items] :as workload} (first (jdbc/query tx select))]
      (util/do-or-nil (update-workload! tx cromwell items))
      (-> workload
          (assoc :workflows (->> items
                                 (format "SELECT * FROM %s")
                                 (jdbc/query tx)
                                 (mapv unnilify)))
          unnilify))))

(defn reset-debug-db
  "Drop everything managed by Liquibase from the :debug DB."
  []
  (jdbc/with-db-transaction [db (zero-db-config :debug)]
    (let [wq (str/join " " ["SELECT 1 FROM pg_catalog.pg_tables"
                            "WHERE tablename = 'workload'"])
          tq (str/join " " ["SELECT 1 FROM pg_type"
                            "WHERE typname = 'pipeline'"])
          eq "SELECT UNNEST(ENUM_RANGE(NULL::pipeline))"]
      (when (seq (jdbc/query db wq))
        (doseq [{:keys [items]} (jdbc/query db "SELECT items FROM workload")]
          (jdbc/db-do-commands db (str "DROP TABLE " items)))
        (jdbc/db-do-commands db "DROP TABLE workload"))
      (when (seq (jdbc/query db tq))
        (doseq [enum (jdbc/query db eq)]
          (jdbc/db-do-commands db (str "DROP TYPE " (:unnest enum))))
        (jdbc/db-do-commands db "DROP TYPE IF EXISTS pipeline")))
    (jdbc/db-do-commands db "DROP TABLE IF EXISTS databasechangelog")
    (jdbc/db-do-commands db "DROP TABLE IF EXISTS databasechangeloglock")))

(comment
  (str/join " " ["liquibase" "--classpath=$(clojure -Spath)"
                 "--url=jdbc:postgresql:wfl"
                 "--changeLogFile=database/changelog.xml"
                 "--username=$USER" "update"])
  (str/join " " ["pg_ctl" "-D" "/usr/local/var/postgresql@11" "start"])
  (run-liquibase)
  (reset-debug-db)
  (run-liquibase :gotc-dev)
  (zero-db-config :gotc-dev)
  (query :debug "SELECT * FROM workload")
  )

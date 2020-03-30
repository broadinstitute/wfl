(ns zero.service.postgres
  "Talk to the Postgres database."
  (:require [clojure.java.jdbc :as jdbc]
            [clojure.data.json :as json]
            [clojure.string :as str]
            [clj-http.client :as http]
            [zero.environments :as env]
            [zero.module.wgs :as wgs]
            [zero.once :as once]
            [zero.util :as util]
            [zero.zero :as zero])
  (:import [liquibase.integration.commandline Main]))

(defn zero-db-url
  "Nil or an URL for the zero-postgresql service in ENVIRONMENT."
  [environment]
  (when-let [{:keys [project vault]} (get-in env/stuff [environment :server])]
    (letfn [(postgresql? [{:keys [instanceType name region]}]
              (when (= [instanceType         name]
                       ["CLOUD_SQL_INSTANCE" "zero-postgresql"])
                (str "jdbc:postgresql://google/postgres?useSSL=false"
                     "&socketFactory="
                     "com.google.cloud.sql.postgres.SocketFactory"
                     "&cloudSqlInstance="
                     (str/join ":" [project region name]))))]
      (-> {:method :get
           :url (str "https://www.googleapis.com/sql/v1beta4/projects/"
                     project "/instances")
           :headers (merge {"Content-Type" "application/json"}
                           (once/get-local-auth-header))}
          http/request
          :body
          (json/read-str :key-fn keyword)
          :items
          (->> (keep postgresql?))
          first))))

(defn zero-db-config
  "Get the config for the zero database in ENVIRONMENT."
  [environment]
  (let [vault (get-in env/stuff [environment :server :vault])
        {:keys [password username]} (util/vault-secrets vault)]
    (assoc {:instance-name "zero-postgresql"
            :db-name       "postgres"
            :classname     "org.postgresql.Driver"
            :subprotocol   "postgresql"
            :vault         "secret/dsde/gotc/dev/zero"}
           :connection-uri (zero-db-url environment)
           :user username
           :password password)))

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
   (run-liquibase-update "jdbc:postgresql:postgres"
                         (util/getenv "USER" "postgres")
                         "password")))

;; Works only with WGS now.
;;
(defn add-pipeline-table!
  "Add a pipeline table for BODY to DB in ENVIRONMENT."
  [environment body]
  (jdbc/with-db-transaction [db (zero-db-config environment)]
    (let [{:keys [commit version] :as the-version} (zero/get-the-version)
          {:keys [creator cromwell input output pipeline project]} body
          {:keys [release top]} wgs/workflow-wdl
          row (jdbc/insert! db :workload {:commit   commit
                                          :creator  creator
                                          :cromwell cromwell
                                          :input    input
                                          :output   output
                                          :pipeline pipeline
                                          :project  project
                                          :release  release
                                          :version  version
                                          :wdl      top})
          work (str/join "." [pipeline (:id row)])]
      (jdbc/update! db :workload {:load load})
      (jdbc/db-do-commands
        db (format "CREATE TABLE %s OF TYPE %s (PRIMARY KEY (id))"
                   pipeline load))
      (jdbc/insert-multi! db load (util/map-csv (:workflows body))))))

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
        (doseq [load (jdbc/query db "SELECT load FROM workload")]
          (jdbc/db-do-commands db (str "DROP TABLE " load)))
        (jdbc/db-do-commands db "DROP TABLE workload"))
      (when (seq (jdbc/query db tq))
        (doseq [enum (jdbc/query db eq)]
          (jdbc/db-do-commands db (str "DROP TYPE " (:unnest enum))))
        (jdbc/db-do-commands db "DROP TYPE IF EXISTS pipeline")))
    (jdbc/db-do-commands db "DROP TABLE IF EXISTS databasechangelog")
    (jdbc/db-do-commands db "DROP TABLE IF EXISTS databasechangeloglock")))

(comment
  (str/join " " ["liquibase" "--classpath=$(clojure -Spath)"
                 "--url=jdbc:postgresql:postgres"
                 "--changeLogFile=database/changelog.xml"
                 "--username=$USER" "update"])
  (str/join " " ["pg_ctl" "-D" "/usr/local/var/postgresql@11" "start"])
  (run-liquibase)
  (reset-debug-db)
  (run-liquibase :gotc-dev)
  (zero-db-config :gotc-dev)
  (query   :gotc-dev "SELECT 3*5 AS result")
  (query   :gotc-dev "SELECT * FROM workload")
  (query   :debug "SELECT * FROM workload")
  (insert! :debug
           "workload" {:project_id "UKB123"
                       :pipeline "WhiteAlbumExomeReprocessing"
                       :cromwell_instance "gotc-dev"
                       :input_path "gs://broad-gotc-dev-white-album/"
                       :output_path "gs://gotc-us-testbucket2/"})
  )

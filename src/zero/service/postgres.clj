(ns zero.service.postgres
  "Talk to the Postgres database."
  (:require [clojure.java.jdbc :as jdbc]
            [clojure.data.json :as json]
            [clojure.string :as str]
            [clj-http.client :as http]
            [zero.environments :as env]
            [zero.once :as once]
            [zero.util :as util])
  (:import [liquibase.integration.commandline Main]))

(defn cloud-db-url
  "Return NIL or an URL for the CloudSQL instance containing DB-NAME."
  [environment instance-name db-name]
  (if-let [gcp (get-in env/stuff [environment :google-cloud-project])]
    (letfn [(postgresql? [{:keys [instanceType name region]}]
              (when (= [instanceType         name]
                       ["CLOUD_SQL_INSTANCE" instance-name])
                (str "jdbc:postgresql://google/" db-name "?useSSL=false"
                     "&socketFactory="
                     "com.google.cloud.sql.postgres.SocketFactory"
                     "&cloudSqlInstance="
                     (str/join ":" [gcp region name]))))]
      (-> {:method :get
           :url (str "https://www.googleapis.com/sql/v1beta4/projects/"
                     gcp "/instances")
           :headers (merge {"Content-Type" "application/json"}
                           (once/get-local-auth-header))}
          http/request
          :body
          (json/read-str :key-fn keyword)
          :items
          (->> (keep postgresql?))
          first))))

(defn get-db-config
  "Get the config for the database in ENVIRONMENT SCHEMA."
  [environment schema]
  (let [db-stuff (get-in env/stuff [environment schema])
        {:keys [vault instance-name db-name]} db-stuff
        {:keys [password username]} (util/vault-secrets vault)]
    (assoc db-stuff
           :connection-uri (cloud-db-url environment instance-name db-name)
           :user username
           :password password)))

(defn query
  "Query the database with SCHEMA in ENVIRONMENT with SQL."
  [environment schema sql]
  (jdbc/query (get-db-config environment schema) sql))

(defn insert!
  "Add ROW map to TABLE in the database with SCHEMA in ENVIRONMENT."
  [environment schema table row]
  (jdbc/insert! (get-db-config environment schema) table row))

(defn run-liquibase
  "Migrate the database schema using Liquibase."
  [env]
  (let [{:keys [instance-name db-name vault]} (:zero-db (env env/stuff))
        {:keys [username password]} (util/vault-secrets vault)
        status (Main/run
                 (into-array
                   String
                   [(str "--url=" (cloud-db-url env instance-name db-name))
                    (str "--changeLogFile=database/migration/changelog.xml")
                    (str "--username=" username)
                    (str "--password=" password)
                    "update"]))]
    (when-not (zero? status)
      (throw
        (Exception.
          (format "Liquibase migration failed with: %s" status))))))

(comment
  (query   :gotc-dev :zero-db "SELECT 3*5 AS result")
  (query   :gotc-dev :zero-db "SELECT * FROM workload")
  (insert! :gotc-dev :zero-db
           "workload" {:project_id "UKB123"
                       :pipeline "WhiteAlbumExomeReprocessing"
                       :cromwell_instance "gotc-dev"
                       :input_path "gs://broad-gotc-dev-white-album/"
                       :output_path "gs://gotc-us-testbucket2/"})
  )

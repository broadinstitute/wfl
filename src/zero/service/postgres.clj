(ns zero.service.postgres
  "Talk to the Postgres database."
  (:require [clojure.data.json :as json]
            [clojure.java.jdbc :as jdbc]
            [clojure.string :as str]
            [clj-http.client :as http]
            [zero.environments :as env]
            [zero.once :as once]
            [zero.service.cromwell :as cromwell]
            [zero.util :as util])
  (:import [java.time OffsetDateTime]
           [liquibase.integration.commandline Main]))

(defn zero-db-config
  "Get the database configuration."
  []
  (let [{:strs [USER
                ZERO_POSTGRES_PASSWORD
                ZERO_POSTGRES_URL
                ZERO_POSTGRES_USERNAME]} (util/getenv)]
    (assoc {:instance-name "zero-postgresql"
            :db-name       "wfl"
            :classname     "org.postgresql.Driver"
            :subprotocol   "postgresql"}
      :connection-uri (or ZERO_POSTGRES_URL "jdbc:postgresql:wfl")
      :password       (or ZERO_POSTGRES_PASSWORD "password")
      :user           (or ZERO_POSTGRES_USERNAME USER "postgres"))))

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
   (let [{:keys [username password postgres_url]}
         (-> env/stuff env :server :vault util/vault-secrets)]
     (run-liquibase-update postgres_url username password)))
  ([]
   (run-liquibase-update
     "jdbc:postgresql:wfl" (util/getenv "USER" "postgres") "password")))

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
       :headers (once/get-auth-header)}
    http/request :body
    (json/read-str :key-fn keyword)
    :status util/do-or-nil))

(defn update-workflow-status!
  "Use TX to update the status of WORKFLOW in ITEMS table."
  [tx cromwell items {:keys [id uuid] :as _workflow}]
  (letfn [(maybe [m k v] (if v (assoc m k v) m))]
    (when uuid
      (let [now    (OffsetDateTime/now)
            status (if (util/uuid-nil? uuid) "skipped"
                       (cromwell-status cromwell uuid))]
        (jdbc/update! tx items
          (maybe {:updated now :uuid uuid} :status status)
          ["id = ?" id])))))

(defn update-workload!
  "Use transaction TX to update _WORKLOAD statuses."
  [tx {:keys [cromwell id items] :as _workload}]
  (->> items
    (get-table tx)
    (run! (partial update-workflow-status! tx cromwell items)))
  (let [finished? (set (conj cromwell/final-statuses "skipped"))]
    (when (every? (comp finished? :status) (get-table tx items))
      (jdbc/update! tx :workload
        {:finished (OffsetDateTime/now)}
        ["id = ?" id]))))

(defn get-workload-for-uuid
  "Use transaction TX to return workload with UUID."
  [tx {:keys [uuid]}]
  (letfn [(unnilify [m] (into {} (filter second m)))]
    (let [select   ["SELECT * FROM workload WHERE uuid = ?" uuid]
          {:keys [items] :as workload} (first (jdbc/query tx select))]
      (util/do-or-nil (update-workload! tx workload))
      (-> workload
        (assoc :workflows (->> items
                            (format "SELECT * FROM %s")
                            (jdbc/query tx)
                            (mapv unnilify)))
        unnilify))))

(comment
  (str/join " " ["liquibase" "--classpath=$(clojure -Spath)"
                 "--url=jdbc:postgresql:wfl"
                 "--changeLogFile=database/changelog.xml"
                 "--username=$USER" "update"])
  (str/join " " ["pg_ctl" "-D" "/usr/local/var/postgresql@11" "start"])
  (run-liquibase)
  )

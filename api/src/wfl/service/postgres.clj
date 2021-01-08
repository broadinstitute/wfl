(ns wfl.service.postgres
  "Talk to the Postgres database."
  (:require [clojure.string :as str]
            [clj-http.client :as http]
            [wfl.jdbc :as jdbc]
            [wfl.once :as once]
            [wfl.service.cromwell :as cromwell]
            [wfl.service.terra :as terra]
            [wfl.util :as util]
            [wfl.module.all :as all])
  (:import [java.time OffsetDateTime]))

(defn wfl-db-config
  "Get the database configuration."
  []
  (let [{:strs [USER
                WFL_POSTGRES_PASSWORD
                WFL_POSTGRES_URL
                WFL_POSTGRES_USERNAME]} (util/getenv)]
    (assoc {:classname       "org.postgresql.Driver"
            :db-name         "wfl"
            :instance-name   "zero-postgresql"
            ;; https://www.postgresql.org/docs/9.1/transaction-iso.html
            :isolation-level :serializable
            :subprotocol     "postgresql"}
           :connection-uri (or WFL_POSTGRES_URL "jdbc:postgresql:wfl")
           :password (or WFL_POSTGRES_PASSWORD "password")
           :user (or WFL_POSTGRES_USERNAME USER "postgres"))))

(defn table-exists?
  "Check if TABLE exists using transaction TX."
  [tx table]
  (->> ["SELECT * FROM INFORMATION_SCHEMA.TABLES WHERE TABLE_NAME = ?" (str/lower-case table)]
       (jdbc/query tx)
       (count)
       (not= 0)))

(defn get-table
  "Return TABLE using transaction TX."
  [tx table]
  (if (and table (table-exists? tx table))
    (jdbc/query tx (format "SELECT * FROM %s" table))
    (throw (ex-info (format "Table %s does not exist" table) {:cause "no-such-table"}))))

(defn ^:private cromwell-status
  "`status` of the workflow with `uuid` in `cromwell`."
  [cromwell uuid]
  (-> (str/join "/" [cromwell "api" "workflows" "v1" uuid "status"])
      (http/get {:headers (once/get-auth-header)})
      :body
      util/parse-json
      :status))

(def ^:private finished?
  "Test if a workflow `:status` is in a terminal state."
  (set (conj cromwell/final-statuses "skipped")))

(defn ^:private make-update-workflows [get-status!]
  (fn [tx {:keys [items workflows] :as workload}]
    (letfn [(update! [{:keys [id uuid]} status]
              (jdbc/update! tx items
                            {:updated (OffsetDateTime/now) :uuid uuid :status status}
                            ["id = ?" id]))]
      (->> workflows
           (remove (comp nil? :uuid))
           (remove (comp finished? :status))
           (run! #(update! % (get-status! workload %)))))))

(def update-workflow-statuses!
  "Use `tx` to update `status` of Cromwell `workflows` in a `workload`."
  (letfn [(get-cromwell-status [{:keys [cromwell]} {:keys [uuid]}]
            (if (util/uuid-nil? uuid)
              "skipped"
              (cromwell-status cromwell uuid)))]
    (make-update-workflows get-cromwell-status)))

(def update-terra-workflow-statuses!
  "Use `tx` to update `status` of Terra `workflows` in a `workload`."
  (letfn [(get-terra-status [{:keys [cromwell project]} workflow]
            (terra/get-workflow-status-by-entity cromwell project workflow))]
    (make-update-workflows get-terra-status)))

(defn batch-update-workflow-statuses!
  "Use `tx` to update `status` the workflows in a `workload`."
  [tx {:keys [executor uuid items]}]
  (let [uuid->status (->> {:label (str "workload:" uuid) :includeSubworkflows "false"}
                          (cromwell/query (first (all/cromwell-environments executor)))
                          (map (juxt :id :status)))]
    (letfn [(update! [[uuid status]]
              (jdbc/update! tx items
                            {:updated (OffsetDateTime/now) :status status}
                            ["uuid = ?" uuid]))]
      (run! update! uuid->status))))

(defn update-workload-status!
  "Use `tx` to mark `workload` finished when all `workflows` are finished."
  [tx {:keys [id items] :as _workload}]
  (let [query (format "SELECT id FROM %%s WHERE status IS NULL OR status NOT IN %s"
                      (util/to-quoted-comma-separated-list finished?))]
    (when (empty? (jdbc/query tx (format query items)))
      (jdbc/update! tx :workload
                    {:finished (OffsetDateTime/now)} ["id = ?" id]))))

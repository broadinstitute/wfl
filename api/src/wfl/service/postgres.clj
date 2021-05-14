(ns wfl.service.postgres
  "Talk to the Postgres database."
  (:require [clojure.string        :as str]
            [clj-http.client       :as http]
            [wfl.environment       :as env]
            [wfl.jdbc              :as jdbc]
            [wfl.auth              :as auth]
            [wfl.service.cromwell  :as cromwell]
            [wfl.service.firecloud :as firecloud]
            [wfl.util              :as util])
  (:import [java.time OffsetDateTime]))

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

(defn table-length [tx table-name]
  (when-not (table-exists? tx table-name)
    (throw (ex-info "No such table" {:table table-name})))
  (->> (format "SELECT COUNT(*) FROM %s" table-name)
       (jdbc/query tx)
       first
       :count))

(defn ^:private cromwell-status
  "`status` of the workflow with `uuid` in `cromwell`."
  [cromwell uuid]
  (-> (str/join "/" [cromwell "api" "workflows" "v1" uuid "status"])
      (http/get {:headers (auth/get-auth-header)})
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
                            {:status status
                             :updated (OffsetDateTime/now)
                             :uuid uuid}
                            ["id = ?" id]))]
      (->> workflows
           (remove (comp nil? :uuid))
           (remove (comp finished? :status))
           (run! #(update! % (get-status! workload %)))))))

(def update-workflow-statuses!
  "Use `tx` to update `status` of Cromwell `workflows` in a `workload`."
  (letfn [(get-cromwell-status [{:keys [executor]} {:keys [uuid]}]
            (if (util/uuid-nil? uuid)
              "skipped"
              (cromwell-status executor uuid)))]
    (make-update-workflows get-cromwell-status)))

(def update-terra-workflow-statuses!
  "Use `tx` to update `status` of Terra `workflows` in a `workload`."
  (letfn [(get-terra-status [{:keys [project]} workflow]
            (firecloud/get-workflow-status-by-entity project workflow))]
    (make-update-workflows get-terra-status)))

(defn batch-update-workflow-statuses!
  "Use `tx` to update the `status` of the workflows in `_workload`."
  [tx {:keys [executor uuid items] :as _workoad}]
  (let [uuid->status (->> {:label (str "workload:" uuid) :includeSubworkflows "false"}
                          (cromwell/query executor)
                          (map (juxt :id :status)))]
    (letfn [(update! [[uuid status]]
              (jdbc/update! tx items
                            {:status status :updated (OffsetDateTime/now)}
                            ["uuid = ?" uuid]))]
      (run! update! uuid->status))))

(defn active-workflows
  "Use `tx` to query all the workflows in `_workload` whose :status is not in
  `finished?`"
  [tx {:keys [items] :as _workload}]
  (let [query "SELECT id FROM %s WHERE status IS NULL OR status NOT IN %s"]
    (->> (util/to-quoted-comma-separated-list finished?)
         (format query items)
         (jdbc/query tx))))

(defn update-workload-status!
  "Use `tx` to mark `workload` finished when all `workflows` are finished."
  [tx {:keys [id] :as workload}]
  (when (empty? (active-workflows tx workload))
    (jdbc/update! tx :workload {:finished (OffsetDateTime/now)} ["id = ?" id])))

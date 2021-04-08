(ns wfl.service.postgres
  "Talk to the Postgres database."
  (:require [clojure.string :as str]
            [clj-http.client :as http]
            [wfl.environment :as env]
            [wfl.jdbc :as jdbc]
            [wfl.auth :as auth]
            [wfl.reader :refer :all]
            [wfl.service.cromwell :as cromwell]
            [wfl.service.firecloud :as firecloud]
            [wfl.util :as util])
  (:import [java.time OffsetDateTime]))

(defn wfl-db-config
  "Get the database configuration."
  []
  (assoc {:classname       "org.postgresql.Driver"
          :db-name         "wfl"
          :instance-name   "zero-postgresql"
          ;; https://www.postgresql.org/docs/9.1/transaction-iso.html
          :isolation-level :serializable
          :subprotocol     "postgresql"}
         :connection-uri (env/getenv "WFL_POSTGRES_URL")
         :password (env/getenv "WFL_POSTGRES_PASSWORD")
         :user (or (env/getenv "WFL_POSTGRES_USERNAME") (env/getenv "USER") "postgres")))

(def ^:private table-exists-query
  "SELECT COUNT(*) FROM INFORMATION_SCHEMA.TABLES WHERE TABLE_NAME = ?")

(defn table-exists?
  "Return a Reader checking if `table` exists."
  [table]
  {:pre (some? table)}
  (let-m [result (jdbc/query [table-exists-query (str/lower-case table)])]
    (return (not= 0 (-> result first :count)))))

(defn get-table
  "Return a Reader for `table`."
  [table]
  (let-m [exists? (table-exists? table)]
    (unless-m exists?
      (throw (ex-info (format "Table %s does not exist" table)
                      {:cause "no-such-table"})))
    (jdbc/query (format "SELECT * FROM %s" table))))

(def ^:private finished?
  "Test if a workflow `:status` is in a terminal state."
  (set (conj cromwell/final-statuses "skipped")))

(defn ^:private make-update-workflows [get-status!]
  (fn [{:keys [items workflows] :as workload}]
    (letfn [(update! [{:keys [id uuid]} status]
              (jdbc/update! items
                            {:status status
                             :updated (OffsetDateTime/now)
                             :uuid uuid}
                            ["id = ?" id]))]
      (->> workflows
           (remove (comp nil? :uuid))
           (remove (comp finished? :status))
           (mapv #(get-status! workload %))
           (run-m update!)))))

(def update-workflow-statuses!
  "Use `tx` to update `status` of Cromwell `workflows` in a `workload`."
  (letfn [(cromwell-status [{:keys [executor]} {:keys [uuid]}]
            (if (util/uuid-nil? uuid)
              "skipped"
              (cromwell/status executor uuid)))]
    (make-update-workflows cromwell-status)))

(def update-terra-workflow-statuses!
  "Use `tx` to update `status` of Terra `workflows` in a `workload`."
  (letfn [(get-terra-status [{:keys [project]} workflow]
            (firecloud/get-workflow-status-by-entity project workflow))]
    (make-update-workflows get-terra-status)))

(defn batch-update-workflow-statuses!
  "Use `tx` to update the `status` of the workflows in `_workload`."
  [{:keys [items] :as workload}]
  (let [uuid->status (map (juxt :id :status) (cromwell/workflows workload))
        now          (OffsetDateTime/now)]
    (letfn [(update! [[uuid status]]
              (jdbc/update! items
                            {:status status :updated now}
                            ["uuid = ?" uuid]))]
      (run-m update! uuid->status))))

(defn update-workflow-statuses [{:keys [items] :as _workload} workflows]
  (letfn [(update! [now [uuid status]]
            (jdbc/update! items
                          {:status status :updated now}
                          ["uuid = ?" uuid]))]
    (let [now (OffsetDateTime/now)]
      (run! (comp #(update! now %) (juxt :uuid :status)) workflows))))

(def ^:private active-workflow-query
  (format "SELECT id FROM %%s WHERE status IS NULL OR status NOT IN %s"
          (util/to-quoted-comma-separated-list finished?)))

(defn active-workflows
  "Return a Reader that queries all the workflows in `_workload` whose :status
   is not in `finished?`"
  [{:keys [items] :as _workload}]
  (jdbc/query (format active-workflow-query items)))

(defn finished
  ""
  [{:keys [id] :as _workload}]
  (jdbc/update! :workload {:finished (OffsetDateTime/now)} ["id = ?" id]))

(defn update-workload-status!
  "Return a `Reader` mark `workload` finished when all `workflows` are
   finished."
  [{:keys [id] :as workload}]
  (let-m [workflows (active-workflows workload)]
    (when-m (empty? workflows)
      (jdbc/update! :workload
                    {:finished (OffsetDateTime/now)}
                    ["id = ?" id]))))

(ns zero.service.postgres
  "Talk to the Postgres database."
  (:require [clojure.data.json :as json]
            [clojure.string :as str]
            [clj-http.client :as http]
            [zero.jdbc :as jdbc]
            [zero.once :as once]
            [zero.service.cromwell :as cromwell]
            [zero.util :as util])
  (:import [java.time OffsetDateTime]))

(defn zero-db-config
  "Get the database configuration."
  []
  (let [{:strs [USER
                ZERO_POSTGRES_PASSWORD
                ZERO_POSTGRES_URL
                ZERO_POSTGRES_USERNAME]} (util/getenv)]
    (assoc {:classname       "org.postgresql.Driver"
            :db-name         "wfl"
            :instance-name   "zero-postgresql"
            ;; https://www.postgresql.org/docs/9.1/transaction-iso.html
            :isolation-level :serializable
            :subprotocol     "postgresql"}
           :connection-uri (or ZERO_POSTGRES_URL "jdbc:postgresql:wfl")
           :password       (or ZERO_POSTGRES_PASSWORD "password")
           :user           (or ZERO_POSTGRES_USERNAME USER "postgres"))))

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
  (try
    (let [workflows (get-table tx items)]
      (run! (partial update-workflow-status! tx cromwell items) workflows)
      (let [finished? (set (conj cromwell/final-statuses "skipped"))]
        (when (every? (comp finished? :status) workflows)
          (jdbc/update! tx :workload
                        {:finished (OffsetDateTime/now)}
                        ["id = ?" id]))))
    (catch Exception cause
      (throw (ex-info "Error updating workload status" {} cause)))))

(defn get-workload-for-uuid
  "Use transaction TX to return workload with UUID."
  [tx {:keys [uuid]}]
  (letfn [(unnilify [m] (into {} (filter second m)))]
    (let [select   ["SELECT * FROM workload WHERE uuid = ?" uuid]
          {:keys [items] :as workload} (first (jdbc/query tx select))]
      (util/do-or-nil (update-workload! tx workload))
      (try
        (let [workflows (get-table tx items)]
          (-> workload
              (assoc :workflows (mapv unnilify workflows))
              unnilify))
        (catch Exception e
          (unnilify workload))))))

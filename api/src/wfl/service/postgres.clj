(ns wfl.service.postgres
  "Talk to the Postgres database."
  (:require [clojure.data.json :as json]
            [clojure.string :as str]
            [clj-http.client :as http]
            [wfl.jdbc :as jdbc]
            [wfl.once :as once]
            [wfl.service.cromwell :as cromwell]
            [wfl.util :as util])
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

;; HACK: We don't have the workload environment here.
;;
(defn cromwell-status
  "NIL or the status of the workflow with UUID on CROMWELL."
  [cromwell uuid]
  (-> {:method  :get                                        ; :debug true :debug-body true
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
  "Use transaction TX to update WORKLOAD statuses."
  [tx {:keys [cromwell id items workflows] :as _workload}]
  (try
    (run! (partial update-workflow-status! tx cromwell items) workflows)
    (let [finished? (set (conj cromwell/final-statuses "skipped"))]
      (when (every? (comp finished? :status) workflows)
        (jdbc/update! tx :workload
          {:finished (OffsetDateTime/now)}
          ["id = ?" id])))
    (catch Exception cause
      (throw (ex-info "Error updating workload status" {} cause)))))

(defn- make-load-workload [load-workload tx identifier]
  (letfn [(unnilify [m] (into {} (filter second m)))]
    (if-let [workload (load-workload tx identifier)]
      (->>
        (get-table tx (:items workload))
        (map unnilify)
        (assoc workload :workflows)
        unnilify))))

(def load-workload-for-uuid
  "Use transaction `tx` to load `workload` with `uuid`."
  (partial make-load-workload
    (fn [tx uuid]
      (->> ["SELECT * FROM workload WHERE uuid = ?" uuid]
        (jdbc/query tx)
        first))))

(def load-workload-for-id
  "Use transaction `tx` to load `workload` with `id`."
  (partial make-load-workload
    (fn [tx id]
      (->> ["SELECT * FROM workload WHERE id = ?" id]
        (jdbc/query tx)
        first))))

(defn load-workloads
  "Use transaction TX to load all known `workloads`"
  [tx]
  (let [do-load (partial make-load-workload (fn [_ x] x) tx)]
    (map do-load (jdbc/query tx ["SELECT * FROM workload"]))))

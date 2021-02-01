(ns wfl.tools.fixtures
  (:require [clojure.string :as str]
            [wfl.service.gcs :as storage]
            [wfl.service.google.pubsub :as pubsub]
            [wfl.service.postgres :as postgres]
            [wfl.tools.liquibase :as liquibase]
            [wfl.jdbc :as jdbc]
            [wfl.util :as util])
  (:import [java.util UUID]))

(defn method-overload-fixture
  "Temporarily dispatch MULTIFN to OVERLOAD on DISPATCH-VAL"
  [multifn dispatch-val overload]
  (fn [run-test]
    (defmethod multifn dispatch-val [& xs] (apply overload xs))
    (run-test)
    (remove-method multifn dispatch-val)))

(def gcs-test-bucket "broad-gotc-dev-wfl-ptc-test-outputs")
(def delete-test-object (partial storage/delete-object gcs-test-bucket))

(defn with-temporary-cloud-storage-folder
  "Create a temporary folder in the Google Cloud storage `bucket` and call
  `use-folder` with the gs url of the temporary folder. The folder will be
  deleted after execution transfers from `use-folder`.

  Parameters
  ----------
    bucket - name of Google Cloud storage bucket to create temporary folder in
    use    - function to call with gs url of temporary folder

  Example
  -------
    (with-temporary-gcs-folder \"broad-gotc-dev\"
       (fn [url] #_(use temporary folder at url)))
  "
  [bucket use-folder]
  (util/bracket
   #(storage/gs-url bucket (str "wfl-test-" (UUID/randomUUID) "/"))
   #(run! (comp (partial storage/delete-object bucket) :name) (storage/list-objects %))
   use-folder))

(defn ^:private postgres-db-config []
  (-> (postgres/wfl-db-config)
      (dissoc :instance-name)
      (merge {:connection-uri "jdbc:postgresql:postgres"
              :db-name        "postgres"})))

(def testing-db-config
  (let [name (str "wfltest" (str/replace (UUID/randomUUID) "-" ""))]
    (-> (postgres/wfl-db-config)
        (dissoc :instance-name)
        (merge {:connection-uri (str "jdbc:postgresql:" name)
                :db-name        name})
        constantly)))

(defn ^:private create-local-database
  "Create a local PostgreSQL database with DBNAME."
  [dbname]
  (clojure.java.jdbc/with-db-connection [conn (postgres-db-config)]
    (jdbc/db-do-commands conn false (format "CREATE DATABASE %s" dbname))))

(defn ^:private setup-local-database
  "Run liquibase migrations on the local PostgreSQL database `dbname`."
  [dbname]
  (let [changelog "../database/changelog.xml"
        url       (format "jdbc:postgresql:%s" dbname)
        {:keys [user password]} (postgres/wfl-db-config)]
    (liquibase/run-liquibase url changelog user password)))

(defn ^:private drop-local-db
  "Drop the local PostgreSQL database `dbname`."
  [dbname]
  (clojure.java.jdbc/with-db-connection [conn (postgres-db-config)]
    (jdbc/db-do-commands conn false (format "DROP DATABASE %s" dbname))))

(defn temporary-postgresql-database [f]
  "Create a temporary PostgreSQL database whose configuration is
  `testing-db-config` for testing. Assumes that the database does not
  already exist.

  Example
  -------
  ;; Use a clean PostgresSQL database in this test namespace.
  ;; `:once` creates the database once for the duration of the tests.
  (clj-test/use-fixtures :once temporary-postgresql-database)

  (deftest test-requiring-database-access
    (jdbc/with-db-transaction [tx (testing-db-config)]
      ;; use tx
    ))"
  (let [name (:db-name (testing-db-config))]
    (create-local-database name)
    (try
      (setup-local-database name)
      (f)
      (finally
        (drop-local-db name)))))

(defn create-local-database-for-testing
  "Create and run liquibase on a PostgreSQL database whose configuration is
   `config` for testing. Assumes that the database `(:db-name config)` does
   not already exist.
   Notes:
   - This is intended for interactive development in a REPL.
   - The new database will NOT be cleaned up automatically."
  [config]
  (let [name (:db-name config)]
    (create-local-database name)
    (setup-local-database name)))

(defn with-temporary-topic [project f]
  "Create a temporary Google Cloud Storage Pub/Sub topic"
  (util/bracket
   #(pubsub/create-topic project (str "wfl-test-" (UUID/randomUUID)))
   pubsub/delete-topic
   f))

(defn with-temporary-notification-configuration [bucket topic f]
  "Create a temporary Google Cloud Storage Pub/Sub notification configuration"
  (util/bracket
   #(storage/create-notification-configuration bucket topic)
   #(storage/delete-notification-configuration bucket %)
   f))

(defn with-temporary-subscription [topic f]
  "Create a temporary Google Cloud Storage Pub/Sub subscription"
  (util/bracket
   #(pubsub/create-subscription topic (str "wfl-test-subscription-" (UUID/randomUUID)))
   pubsub/delete-subscription
   f))

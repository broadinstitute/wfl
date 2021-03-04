(ns wfl.tools.fixtures
  (:require [clojure.string :as str]
            [wfl.service.datarepo :as datarepo]
            [wfl.service.google.pubsub :as pubsub]
            [wfl.service.google.storage :as gcs]
            [wfl.service.postgres :as postgres]
            [wfl.tools.liquibase :as liquibase]
            [wfl.jdbc :as jdbc]
            [wfl.util :as util]
            [wfl.environment :as env])
  (:import [java.util UUID]
           (java.nio.file Files)
           (org.apache.commons.io FileUtils)
           (java.nio.file.attribute FileAttribute)))

(defn method-overload-fixture
  "Temporarily dispatch MULTIFN to OVERLOAD on DISPATCH-VAL"
  [multifn dispatch-val overload]
  (fn [run-test]
    (defmethod multifn dispatch-val [& xs] (apply overload xs))
    (run-test)
    (remove-method multifn dispatch-val)))

(def gcs-test-bucket "broad-gotc-dev-wfl-ptc-test-outputs")
(def delete-test-object (partial gcs/delete-object gcs-test-bucket))

(defn ^:private postgres-db-config []
  (-> (postgres/wfl-db-config)
      (dissoc :instance-name)
      (merge {:connection-uri "jdbc:postgresql:postgres"
              :db-name        "postgres"})))

(def ^:private test-db-name
  (str "wfltest" (str/replace (UUID/randomUUID) "-" "")))

(defn testing-db-config
  []
  (-> (postgres/wfl-db-config)
      (dissoc :instance-name)
      (merge {:connection-uri (str "jdbc:postgresql:" test-db-name)
              :db-name        test-db-name})))

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

(defn temporary-postgresql-database
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
      #_(use tx)))"
  [f]
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

(defmacro with-fixtures
  "Use 0 or more `fixtures` in `use-fixtures`.
   Parameters
   ----------
     fixtures     - list of fixtures missing the consuming function in the
                    right-most position.
     use-fixtures - N-ary function accepting each fixture parameter.

   Example
   -------
     (with-fixtures [fixture0
                     (fixture1 x)
                     ...
                     fixtureN]
       (fn [[x0 x1 ... xN]] ))"
  [fixtures use-fixtures]
  (letfn [(go [[f & fs] args]
              (if (nil? f)
                `(~use-fixtures ~args)
                (let [x  (gensym)
                      g# `(fn [~x] ~(go fs (conj args x)))]
                  (if (seq? f) (concat f (list g#)) (list f g#)))))]
    (go fixtures [])))

(defn with-temporary-folder
  "Create a temporary folder in the local filesystem and call `use-folder` with
   the path to the temporary folder. The folder will be deleted after execution
   transfers from `use-folder`.

   Parameters
   ----------
     use-folder - function to call with temporary folder

   Example
   -------
     (with-temporary-folder (fn [folder] #_(use folder)))"
  [use-folder]
  (util/bracket
   #(Files/createTempDirectory "wfl-test-" (into-array FileAttribute []))
   #(FileUtils/deleteDirectory (.toFile %))
   (comp use-folder #(.toString %))))

(defn with-temporary-cloud-storage-folder
  "Create a temporary folder in the Google Cloud storage `bucket` and call
   `use-folder` with the gs url of the temporary folder. The folder will be
   deleted after execution transfers from `use-folder`.

   Parameters
   ----------
     bucket     - name of Google Cloud storage bucket to create temporary folder
     use-folder - function to call with gs url of temporary folder

   Example
   -------
     (with-temporary-gcs-folder \"broad-gotc-dev\"
        (fn [url] #_(use temporary folder at url)))"
  [bucket use-folder]
  (util/bracket
   #(gcs/gs-url bucket (str "wfl-test-" (UUID/randomUUID) "/"))
   #(run! (comp (partial gcs/delete-object bucket) :name) (gcs/list-objects %))
   use-folder))

(defn with-temporary-topic
  "Create a temporary Google Cloud Storage Pub/Sub topic."
  [project f]
  (util/bracket
   #(pubsub/create-topic project (str "wfl-test-" (UUID/randomUUID)))
   pubsub/delete-topic
   f))

(defn with-temporary-notification-configuration
  "Create a temporary Google Cloud Storage Pub/Sub notification configuration"
  [bucket topic f]
  (util/bracket
   #(gcs/create-notification-configuration bucket topic)
   #(gcs/delete-notification-configuration bucket %)
   f))

(defn with-temporary-subscription
  "Create a temporary Google Cloud Storage Pub/Sub subscription"
  [topic f]
  (util/bracket
   #(pubsub/create-subscription topic (str "wfl-test-subscription-" (UUID/randomUUID)))
   pubsub/delete-subscription
   f))

(defn with-temporary-dataset
  "Create a temporary Terra Data Repository Dataset with `dataset-request`"
  [dataset-request f]
  (util/bracket
   #(datarepo/create-dataset dataset-request)
   datarepo/delete-dataset
   f))

(defn with-temporary-environment
  "Temporarily override the environment with the key-value mapping in `env`.
   The original environment will be restored after `f` returns. No guarantees
   are made for thread safety - in the same way as you wouldn't use a regex to
   parse xhtml [1], don't mix this with multi-threaded code.

   Parameters
   ----------
     new-env - map of environment variable names to their new values.
     f       - Action to execute in the new environment.

   Example
   -------
     (with-temporary-environment {\"WFL_WFL_URL\" \"http://localhost:3000/\"}
       (fn [] #_(use updated environment)))

   [1]: https://stackoverflow.com/a/1732454"
  [new-env f]
  (util/bracket
   #(let [prev @env/testing] (swap! env/testing merge new-env) prev)
   #(reset! env/testing %)
   (fn [_] (f))))

(defn temporary-environment
  "Adapter for clojure.test/use-fixtures"
  [env]
  (partial with-temporary-environment env))

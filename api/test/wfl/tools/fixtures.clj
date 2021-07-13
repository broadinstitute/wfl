(ns wfl.tools.fixtures
  (:require [clojure.java.jdbc]
            [clojure.tools.logging      :as log]
            [wfl.service.datarepo       :as datarepo]
            [wfl.service.google.pubsub  :as pubsub]
            [wfl.service.google.storage :as gcs]
            [wfl.service.postgres       :as postgres]
            [wfl.tools.liquibase        :as liquibase]
            [wfl.jdbc                   :as jdbc]
            [wfl.util                   :as util]
            [wfl.environment            :as env]
            [wfl.service.firecloud      :as firecloud])
  (:import [java.nio.file.attribute FileAttribute]
           [java.nio.file Files]
           [java.util UUID]
           [org.apache.commons.io FileUtils]))

(defn with-temporary-overload
  "Temporarily dispatch MULTIFN to OVERLOAD on DISPATCH-VAL"
  [multifn dispatch-val overload f]
  (defmethod multifn dispatch-val [& xs] (apply overload xs))
  (try
    (f)
    (finally
      (remove-method multifn dispatch-val))))

(defn method-overload-fixture
  "Temporarily dispatch MULTIFN to OVERLOAD on DISPATCH-VAL"
  [multifn dispatch-val overload]
  (partial with-temporary-overload multifn dispatch-val overload))

(def gcs-test-bucket "broad-gotc-dev-wfl-ptc-test-outputs")
(def delete-test-object (partial gcs/delete-object gcs-test-bucket))

(defn ^:private postgres-db-config []
  (-> (postgres/wfl-db-config)
      (merge {:connection-uri "jdbc:postgresql:postgres"
              :db-name        "postgres"
              :instance-name  nil})))

(def testing-db-name
  "The name of the test database"
  (util/randomize "wfltest"))

(defn ^:private create-local-database
  "Create a local PostgreSQL database with DBNAME."
  [dbname]
  (let [config (postgres-db-config)]
    (clojure.java.jdbc/with-db-connection [conn config]
      (jdbc/db-do-commands conn false (format "CREATE DATABASE %s" dbname))
      (merge config {:connection-uri (str "jdbc:postgresql:" dbname)
                     :db-name        dbname}))))

(defn ^:private setup-local-database
  "Run liquibase migrations using PostgreSQL database `config`."
  [config]
  (liquibase/run-liquibase "../database/changelog.xml" config))

(defn ^:private drop-local-db
  "Drop the local PostgreSQL database `dbname`."
  [{:keys [db-name]}]
  (clojure.java.jdbc/with-db-connection [conn (postgres-db-config)]
    (jdbc/db-do-commands conn false (format "DROP DATABASE %s" db-name))))

(defn temporary-postgresql-database
  "Create a temporary PostgreSQL database named `testing-db-name` and
   reconfigure `postgres/wfl-db-config` to use it for testing. Assumes that
   the database does not already exist.

   Example
   -------
   ;; Use a clean PostgresSQL database in this test namespace.
   ;; `:once` creates the database once for the duration of the tests.
   (clj-test/use-fixtures :once temporary-postgresql-database)

   (deftest test-requiring-database-access
     (jdbc/with-db-transaction [tx (wfl-db-config)]
      #_(use tx)))"
  [f]
  (util/bracket
   #(create-local-database testing-db-name)
   drop-local-db
   (fn [config]
     (setup-local-database config)
     (let [prev @@#'postgres/testing-db-overrides]
       (swap! @#'postgres/testing-db-overrides merge config)
       (try
         (f)
         (finally
           (reset! @#'postgres/testing-db-overrides prev)))))))

(defn create-local-database-for-testing
  "Create and run liquibase on a PostgreSQL database named `dbname`. Assumes
   that `dbname` does not already exist.
   Notes:
   - This is intended for interactive development in a REPL.
   - The new database will NOT be cleaned up automatically."
  [dbname]
  (-> dbname create-local-database setup-local-database))

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

(defn with-temporary-snapshot
  "Create a temporary Terra Data Repository Snapshot with `snapshot-request`"
  [snapshot-request f]
  (util/bracket
   #(datarepo/create-snapshot snapshot-request)
   datarepo/delete-snapshot
   f))

(defn with-temporary-workspace
  "Create and use a temporary Terra Workspace."
  ([workspace-prefix group f]
   (util/bracket
    #(doto (util/randomize workspace-prefix) (firecloud/create-workspace group))
    firecloud/delete-workspace
    f))
  ([f]
   (with-temporary-workspace "wfl-dev/test-workspace" "workflow-launcher-dev" f)))

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

(defmacro bind-fixture
  "Returns a closure that can be used with clojure.test/use-fixtures in which
   `var` is bound to the resource created by applying `fixture` to its
   `arguments`."
  [var with-fixture & arguments]
  `(fn [f#]
     (log/infof "Setting up %s..." '~with-fixture)
     (~with-fixture
      ~@arguments
      #(binding [~var %]
         (try (f#)
              (finally (log/infof "Tearing down %s..." '~with-fixture)))))))

(ns wfl.tools.fixtures
  (:require [wfl.service.gcs :as gcs]
            [wfl.util :as util]
            [wfl.environments :as env]
            [wfl.once :as once]
            [wfl.service.postgres :as postgres]
            [clojure.data.json :as json]
            [clj-http.client :as http]
            [clojure.string :as str])
  (:import [java.util UUID]
           [liquibase.integration.commandline Main]))

(defn method-overload-fixture
  "Temporarily dispatch MULTIFN to OVERLOAD on DISPATCH-VAL"
  [multifn dispatch-val overload]
  (fn [run-test]
    (defmethod multifn dispatch-val [& xs] (apply overload xs))
    (run-test)
    (remove-method multifn dispatch-val)))

(def gcs-test-bucket "broad-gotc-dev-wfl-ptc-test-outputs")
(def delete-test-object (partial gcs/delete-object gcs-test-bucket))

(defmacro with-temporary-gcs-folder
  "
  Create a temporary folder in GCS-TEST-BUCKET for use in BODY.
  The folder will be deleted after execution transfers from BODY.

  Example
  -------
    (with-temporary-gcs-folder uri
      ;; use temporary folder at `uri`)
      ;; <- temporary folder deleted
  "
  [uri & body]
  `(let [name# (str "wfl-test-" (UUID/randomUUID) "/")
         ~uri (gcs/gs-url gcs-test-bucket name#)]
     (try ~@body
          (finally
            (->>
             (gcs/list-objects gcs-test-bucket name#)
             (run! (comp delete-test-object :name)))))))

(defn- cloud-db-url
  "Return NIL or an URL for the CloudSQL instance containing DB-NAME."
  [environment instance-name db-name]
  (if-let [gcp (get-in env/stuff [environment :server :project])]
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
                           (once/get-auth-header))}
          http/request
          :body
          (json/read-str :key-fn keyword)
          :items
          (->> (keep postgresql?))
          first))))

(defn- run-liquibase
  "Migrate the database schema using Liquibase."
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

(def testing-dbname
  (:db-name (postgres/wfl-db-config)))

(defn- create-db
  "Create the ad-hoc testing database with DBNAME."
  [dbname]
  (if (System/getenv "WFL_DEPLOY_ENVIRONMENT")
    ; cloud
    (util/shell! "gcloud" "sql" "databases" "create" dbname "-i" "zero-postgresql")
    ; local
    (util/shell! "createdb" dbname)))

(defn- setup-db
  "Setup te db by running liquibase migrations by DBNAME."
  [dbname]
  (if-let [test-env (System/getenv "WFL_DEPLOY_ENVIRONMENT")]
    ; cloud
    (let [url (cloud-db-url :gotc-dev "zero-postgresql" dbname)
          {:keys [username password]}
          (-> env/stuff test-env :server :vault util/vault-secrets)]
      (run-liquibase url username password))
    ; local
    (run-liquibase (format "jdbc:postgresql:%s" dbname) (System/getenv "USER") "")))

(defn- destroy-db
  "Tear down the testing database by DBNAME."
  [dbname]
  (if (System/getenv "WFL_DEPLOY_ENVIRONMENT")
    ; cloud
    (util/shell! "gcloud" "sql" "databases" "delete" dbname "-i" "zero-postgresql")
    ; local
    (util/shell! "dropdb" dbname)))

(defn clean-db-fixture [f]
  "Wrapper for F so it runs in a clean db per invocation.
  This assumes not database with name testing-dbname
  has been manually created in the current testing environment."
  (try
    (create-db testing-dbname)
    (setup-db testing-dbname)
    (f)
    (finally
      (destroy-db testing-dbname))))

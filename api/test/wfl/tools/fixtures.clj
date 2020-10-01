(ns wfl.tools.fixtures
  (:require [clj-http.client :as http]
            [clojure.data.json :as json]
            [clojure.string :as str]
            [wfl.environments :as env]
            [wfl.once :as once]
            [wfl.service.gcs :as gcs]
            [wfl.service.postgres :as postgres]
            [wfl.tools.liquibase :as liquibase]
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

(defn cloud-db-url
  "Return NIL or an URL for the CloudSQL instance containing DB-NAME."
  [environment instance-name db-name]
  (if-let [gcp (get-in env/stuff [environment :server :project])]
    (letfn [(postgresql? [{:keys [instanceType name region]}]
              (when (= [instanceType name]
                      ["CLOUD_SQL_INSTANCE" instance-name])
                (str "jdbc:postgresql://google/" db-name "?useSSL=false"
                  "&socketFactory="
                  "com.google.cloud.sql.postgres.SocketFactory"
                  "&cloudSqlInstance="
                  (str/join ":" [gcp region name]))))]
      (-> {:method  :get
           :url     (str "https://www.googleapis.com/sql/v1beta4/projects/"
                      gcp "/instances")
           :headers (merge {"Content-Type" "application/json"}
                      (once/get-auth-header))}
        http/request
        :body
        (json/read-str :key-fn keyword)
        :items
        (->> (keep postgresql?))
        first))))

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

(defn setup-db
  "Setup te db by running liquibase migrations by DBNAME."
  [dbname]
  (let [changelog "../database/changelog.xml"]
    (if-let [test-env (System/getenv "WFL_DEPLOY_ENVIRONMENT")]
      (let [url     (cloud-db-url :gotc-dev "zero-postgresql" dbname)
            secrets (-> env/stuff test-env :server :vault util/vault-secrets)]
        (liquibase/run-liquibase url changelog (:username secrets) (:password secrets)))
      (let [url      (format "jdbc:postgresql:%s" dbname)
            username (System/getenv "USER")]
        (liquibase/run-liquibase url changelog username)))))

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

(ns wfl.tools.fixtures
  (:require [wfl.service.gcs :as gcs]
            [wfl.service.postgres :as postgres]
            [wfl.tools.liquibase :as liquibase]
            [wfl.jdbc :as jdbc]
            [clojure.string :as str])
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

(defn- create-db
  "Create the ad-hoc testing database with DBNAME."
  [dbname]
  (clojure.java.jdbc/with-db-connection [conn (postgres-db-config)]
    (jdbc/db-do-commands conn false (format "CREATE DATABASE %s" dbname))))

(defn setup-db
  "Setup te db by running liquibase migrations by DBNAME."
  [dbname]
  (let [changelog "../database/changelog.xml"
        url       (format "jdbc:postgresql:%s" dbname)
        {:keys [user password]} (postgres/wfl-db-config)]
    (liquibase/run-liquibase url changelog user password)))

(defn- destroy-db
  "Tear down the testing database by DBNAME."
  [dbname]
  ; local
  (clojure.java.jdbc/with-db-connection [conn (postgres-db-config)]
    (jdbc/db-do-commands conn false (format "DROP DATABASE %s" dbname))))

(defn clean-db-fixture [f]
  "Wrapper for F so it runs in a clean db per invocation.
  This assumes not database with name testing-dbname
  has been manually created in the current testing environment."
  (let [name (:db-name (testing-db-config))]
    (create-db name)
    (try
      (setup-db name)
      (f)
      (finally
        (destroy-db name)))))

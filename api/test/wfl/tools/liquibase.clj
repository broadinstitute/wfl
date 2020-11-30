(ns wfl.tools.liquibase
  (:require [wfl.util :as util])
  (:import [liquibase.integration.commandline Main]))

(defn run-liquibase
  "Migrate the database schema using Liquibase."
  ([url changelog username password]
   (let [status (Main/run
                 (into-array
                  String
                  [(str "--url=" url)
                   (str "--changeLogFile=" changelog)
                   (str "--username=" username)
                   (str "--password=" password)
                   "update"]))]
     (when-not (zero? status)
       (throw
        (Exception.
         (format "Liquibase failed with: %s" status))))))
  ([url changelog username]
   (run-liquibase url changelog username nil)))

(defn -main
  "Migrate the local database schema using Liquibase."
  []
  (let [user (util/getenv "USER")]
    (run-liquibase "jdbc:postgresql:wfl" "../database/changelog.xml" user)))

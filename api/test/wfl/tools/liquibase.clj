(ns wfl.tools.liquibase
  (:require [wfl.service.postgres :as postgres])
  (:import [liquibase.integration.commandline Main]
           [clojure.lang ExceptionInfo]))

(defn run-liquibase
  "Migrate the database schema using Liquibase."
  [changelog {:keys [user password connection-uri]}]
  (let [status (Main/run
                 (into-array
                  String
                  [(str "--url=" connection-uri)
                   (str "--changeLogFile=" changelog)
                   (str "--username=" user)
                   (str "--password=" password)
                   "update"]))]
    (when-not (zero? status)
      (throw (ex-info "Liquibase migration failed" {:status status})))))

(defn -main
  "Migrate the local database schema using Liquibase."
  []
  (try
    (run-liquibase "../database/changelog.xml" (postgres/wfl-db-config))
    #_(System/exit 0)
    (catch ExceptionInfo e
      (binding [*out* *err*]
        (-> e .getMessage println)
        (-> e .getData :status System/exit)))))

(comment (-main))                       ; Aid testing in REPL.

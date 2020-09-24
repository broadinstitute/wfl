(ns wfl.tools.liquibase
  (:require [wfl.util :as util])
  (:import [liquibase.integration.commandline Main]))

(defn -main
  "Migrate the local database schema using Liquibase."
  []
  (let [status (Main/run
                 (into-array String
                   ["--url=jdbc:postgresql:wfl"
                    "--changeLogFile=../database/changelog.xml"
                    (str "--username=" (util/getenv "USER"))
                    "update"]))]
    (when-not (zero? status)
      (throw
        (Exception.
          (format "Liquibase migration failed with: %s" status))))))

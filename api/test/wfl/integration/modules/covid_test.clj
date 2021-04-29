(ns wfl.integration.modules.covid-test
  (:require [clojure.test :refer :all]
            [clojure.test :as clj-test]
            [wfl.tools.fixtures :as fixtures]
            [wfl.jdbc :as jdbc]
            [wfl.api.workloads :as workloads]))

(clj-test/use-fixtures :once fixtures/temporary-postgresql-database)

(deftest test-create-covid-workflow
  (jdbc/with-db-transaction [tx (fixtures/testing-db-config)]
                            (workloads/create-workload! tx {:pipeline "Sarscov2IlluminaFull"
                                                            :source {:name "Terra DataRepo"}
                                                            :executor {:name "Terra",
                                                                       :method_configuration "pathogen-genomic-surveillance/sarscov2_illumina_full"}
                                                            :sink {:name "Terra Workspace",
                                                                   :workspace "pathogen-genomic-surveillance/CDC_Viral_Sequencing"}
                                                            })
                            )
  )


(comment
  (wfl.tools.fixtures/create-local-database-for-testing (wfl.tools.fixtures/testing-db-config))
  )

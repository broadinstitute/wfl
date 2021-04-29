(ns wfl.integration.modules.covid-test
  (:require [clojure.test :refer :all]
            [clojure.test :as clj-test]
            [wfl.tools.fixtures :as fixtures]
            [wfl.jdbc :as jdbc]
            [wfl.api.workloads :as workloads]
            [wfl.service.datarepo :as datarepo]))

(clj-test/use-fixtures :once fixtures/temporary-postgresql-database)
(def ^:private testing-dataset "ff6e2b40-6497-4340-8947-2f52a658f561")
(def ^:private testing-workspace "general-dev-billing-account/test-snapshots")
(def ^:private testing-method-configuration "pathogen-genomic-surveillance/sarscov2_illumina_full")

(deftest test-create-covid-workflow
  (jdbc/with-db-transaction [tx (fixtures/testing-db-config)]
    (workloads/create-workload! tx {:pipeline "Sarscov2IlluminaFull"
                                    :source {:name "Terra DataRepo",
                                             :dataset testing-dataset}
                                    :executor {:name "Terra",
                                               :method_configuration testing-method-configuration}
                                    :sink {:name "Terra Workspace",
                                           :workspace testing-workspace}})))

(comment
  (wfl.tools.fixtures/create-local-database-for-testing (wfl.tools.fixtures/testing-db-config)))

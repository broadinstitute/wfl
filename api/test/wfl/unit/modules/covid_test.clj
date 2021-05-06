(ns wfl.unit.modules.covid-test
  "Test the COVID workload module."
  (:require [clojure.test        :refer :all]
            [clojure.string      :as str]
            [wfl.jdbc            :as jdbc]
            [wfl.module.covid    :as covid]
            [wfl.tools.fixtures  :as fixtures]
            [wfl.tools.workloads :as workloads]
            [wfl.wfl             :as wfl])
  (:import [java.time OffsetDateTime]
           [java.util UUID]))

(use-fixtures :once fixtures/temporary-postgresql-database)

(defn ^:private mock-create
  "Mock covid/create-covid-workload! until it's implemented."
  [tx {:keys [pipeline] :as request}]
  (let [update         (str/join \space ["UPDATE workload"
                                         "SET pipeline = ?::pipeline"
                                         "WHERE id = ?"])
        create         (str/join \space ["CREATE TABLE %s"
                                         "OF ContinuousWorkloadInstance"
                                         "(PRIMARY KEY (id))"])
        [{:keys [id]}] (-> request
                           (dissoc :pipeline :sink :source)
                           (assoc  :commit   ":commit"
                                   :created  (OffsetDateTime/now)
                                   :creator  ":creator"
                                   :executor ":executor"
                                   :output   ":output"
                                   :project  ":project"
                                   :release  ":release"
                                   :uuid     (UUID/randomUUID)
                                   :version  ":version"
                                   :wdl      ":wdl")
                           (->> (jdbc/insert! tx :workload)))
        table          (format "%s_%09d" pipeline id)]
    (jdbc/execute!       tx [update pipeline id])
    (jdbc/db-do-commands tx [(format create table)])
    (jdbc/update!        tx :workload {:items table} ["id = ?" id])
    (wfl.api.workloads/load-workload-for-id tx id)))

(deftest start-workload
  (testing "wfl.module.covid/start-covid-workload!"
    (with-redefs [covid/create-covid-workload! mock-create]
      (let [workload (workloads/create-workload!
                      (workloads/covid-workload-request
                       "dataset" "method-configuration" "namespace/name"))]
        (is (not (:started workload)))
        (is (:started (workloads/start-workload! workload)))))))

(comment (test-vars [#'start-workload]))

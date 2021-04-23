(ns wfl.module.covid
  "Handle COVID processing."
  (:require [clojure.data.json :as json]
            [clojure.set :as set]
            [clojure.string :as str]
            [clojure.tools.logging.readable :as log]
            [wfl.api.workloads :as workloads :refer [defoverload]]
            [wfl.jdbc :as jdbc]
            [wfl.module.batch :as batch]
            [wfl.service.datarepo :as datarepo]
            [wfl.service.firecloud :as firecloud]
            [wfl.service.google.storage :as gcs]
            [wfl.service.postgres :as postgres]
            [wfl.util :as util]
            [wfl.wfl :as wfl]))

(def pipeline "Sarscov2IlluminaFull")

#_(defn ^:private add-workload-table!
    "Use transaction `tx` to add a `CromwellWorkflow` table
  for a `workload-request` running `workflow-wdl`."
    [tx workload-request]
    (let [{:keys [pipeline]} workload-request
          create "CREATE TABLE %s OF CromwellWorkflow (PRIMARY KEY (id))"
          setter "UPDATE workload SET pipeline = ?::pipeline WHERE id = ?"
          [{:keys [id]}]
          (-> workload-request
          ;; FIXME: update the DB schema to write these new information in
              (select-keys [:creator :executor :source :sink :project :labels :watchers])

              (merge (select-keys (wfl/get-the-version) [:commit :version]))
          ;; FIXME: how to write :release and :wdl
              (assoc :release release :wdl path :uuid (UUID/randomUUID))
              (->> (jdbc/insert! tx :workload)))
          table (format "%s_%09d" pipeline id)]
      (jdbc/execute!       tx [setter pipeline id])
      (jdbc/db-do-commands tx [(format create table)])
      (jdbc/update!        tx :workload {:items table} ["id = ?" id])
      [id table]))

;; TODO: implement COVID workload creation
#_(defn create-covid-workload!
    [tx {:keys [source sink executor] :as request}]
  ;; TODO: validation
  ;; TODO: dispatch on source/sink/executor
    (-> request
        (add-workload-table! tx)
        (workloads/load-workload-for-id tx id)))

(comment
  (defn ^:private frob [[k v]]
    (if (map? v)
      [k (json/write-str v :escape-slash false)]
      [k v]))

  ;; Execution error (NoInitialContextException) at javax.naming.spi.NamingManager/getInitialContext (NamingManager.java:691).
  ;; Need to specify class name in environment or system property, or in an application resource file: java.naming.factory.initial
  (jdbc/with-db-transaction [tx (postgres/wfl-db-config)]
    (let [workload-request {:creator  "ranthony@broadinstitute.org",
                            :source
                            {:name          "Terra DataRepo",
                             :dataset       "sarscov2_illumina_full",
                             :dataset_table "inputs",
                             :snapshot      "run_date"},
                            :executor
                            {:name        "Terra",
                             :workspace
                             "pathogen-genomic-surveillance/CDC_Viral_Sequencing",
                             :method_configuration
                             "pathogen-genomic-surveillance/sarscov2_illumina_full",
                             :version     "3",
                             :entity      "snapshot",
                             :from_source "importSnapshot"},
                            :sink
                            {:name         "Terra Workspace",
                             :workspace
                             "pathogen-genomic-surveillance/CDC_Viral_Sequencing",
                             :entity       "flowcell",
                             :from_outputs {:column1 "output1"}},
                            :labels
                            ["project:TestBoston"
                             "project:COVID-19 Surveillance"
                             "pipeline:sarscov2_illumina_full"],
                            :watchers ["cloreth@broadinstitute.org"]}]
      (letfn [(frob [[k v]] (if (map? v) [k (json/write-str v :escape-slash false)] [k v]))
              (go! [k]
                (-> workload-request
                    k
                    (->> (map frob)
                         (into {}))
                    wfl.debug/trace
                    (jdbc/insert! tx k)))]

        (run! go! [:source :executor :sink]))))

(defn start-covid-workload!
  ""
  [])

;; TODO: implement progressive (private) functions inside this update loop
(defn update-covid-workload!
  "Use transaction `tx` to batch-update `workload` statuses."
  [tx {:keys [started finished] :as workload}]
  (letfn [(update! [{:keys [id] :as workload}]
            (postgres/batch-update-workflow-statuses! tx workload)
            (postgres/update-workload-status! tx workload)
            (workloads/load-workload-for-id tx id))]
    (if (and started (not finished)) (update! workload) workload)))

(defoverload workloads/create-workload!   pipeline create-covid-workload!)
(defoverload workloads/start-workload!    pipeline start-covid-workload!)
(defoverload workloads/update-workload!   pipeline update-covid-workload!)
(defoverload workloads/stop-workload!     pipeline batch/stop-workload!)
(defoverload workloads/load-workload-impl pipeline
  batch/load-batch-workload-impl)

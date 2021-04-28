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


;#_(defn ^:private add-workload-table!
;    "Use transaction `tx` to add a `CromwellWorkflow` table
;  for a `workload-request` running `workflow-wdl`."
;    [tx workload-request]
;    (let [{:keys [pipeline]} workload-request
;          create "CREATE TABLE %s OF CromwellWorkflow (PRIMARY KEY (id))"
;          setter "UPDATE workload SET pipeline = ?::pipeline WHERE id = ?"
;          [{:keys [id]}]
;          (-> workload-request
;          ;; FIXME: update the DB schema to write these new information in
;              (select-keys [:creator :executor :source :sink :project :labels :watchers])
;
;              (merge (select-keys (wfl/get-the-version) [:commit :version]))
;          ;; FIXME: how to write :release and :wdl
;              (assoc :release release :wdl path :uuid (UUID/randomUUID))
;              (->> (jdbc/insert! tx :workload)))
;          table (format "%s_%09d" pipeline id)]
;      (jdbc/execute!       tx [setter pipeline id])
;      (jdbc/db-do-commands tx [(format create table)])
;      (jdbc/update!        tx :workload {:items table} ["id = ?" id])
;      [id table]))
;
;;; TODO: implement COVID workload creation
;(defn create-covid-workload!
;  [tx {:keys [source sink executor] :as request}]
;  ;; TODO: validation
;  ;; TODO: dispatch on source/sink/executor
;  #_(-> request
;        (add-workload-table! tx)
;        (workloads/load-workload-for-id tx id)))
;
;(comment
;  (defn ^:private frob [[k v]]
;    (if (map? v)
;      [k (json/write-str v :escape-slash false)]
;      [k v]))
;
;  ;; Execution error (NoInitialContextException) at javax.naming.spi.NamingManager/getInitialContext (NamingManager.java:691).
;  ;; Need to specify class name in environment or system property, or in an application resource file: java.naming.factory.initial
;  (jdbc/with-db-transaction [tx (postgres/wfl-db-config)]
;    (let [workload-request {:creator  "ranthony@broadinstitute.org",
;                            :source
;                            {:name          "Terra DataRepo",
;                             :dataset       "sarscov2_illumina_full",
;                             :dataset_table "inputs",
;                             :snapshot      "run_date"},
;                            :executor
;                            {:name        "Terra",
;                             :workspace
;                             "pathogen-genomic-surveillance/CDC_Viral_Sequencing",
;                             :method_configuration
;                             "pathogen-genomic-surveillance/sarscov2_illumina_full",
;                             :version     "3",
;                             :entity      "snapshot",
;                             :from_source "importSnapshot"},
;                            :sink
;                            {:name         "Terra Workspace",
;                             :workspace
;                             "pathogen-genomic-surveillance/CDC_Viral_Sequencing",
;                             :entity       "flowcell",
;                             :from_outputs {:column1 "output1"}},
;                            :labels
;                            ["project:TestBoston"
;                             "project:COVID-19 Surveillance"
;                             "pipeline:sarscov2_illumina_full"],
;                            :watchers ["cloreth@broadinstitute.org"]}]
;      (letfn [(frob [[k v]] (if (map? v) [k (json/write-str v :escape-slash false)] [k v]))
;              (go! [k]
;                (-> workload-request
;                    k
;                    (->> (map frob)
;                         (into {}))
;                    (jdbc/insert! tx k)))]
;        (run! go! [:source :executor :sink])))))
;
;(defn start-covid-workload!
;  ""
;  [])

(defn verify-source!
  [{:keys [name dataset] :as source}]
  (when-not (= (:name source) "Terra DataRepo")
    (throw (ex-info "Unknown Source" {:source source})))
  ;; TODO: verify workspace exists
  (try
    (datarepo/dataset (:dataset source))
    (catch Throwable _
      (throw (ex-info "Cannot access Dataset" {:dataset dataset})))
    )
  )

(defn verify-executor!
  [executor]
  (when-not (= (:name executor) "Terra")
    (throw (ex-info "Unknown Executor" {:executor executor}))))

(defn verify-sink!
  [sink]
  (when-not (= (:name sink) "Terra Workspace")
    (throw (ex-info "Unknown Sink" {:sink sink}))))

(defn create-covid-workload!
  [tx {:keys [source executor sink] :as workload-request}]
  (verify-source! source)
  (verify-executor! executor)
  (verify-sink! sink)
  )


;(defn ^:private add-workload-table!
;  "Use transaction `tx` to add a `CromwellWorkflow` table
;  for a `workload-request` running `workflow-wdl`."
;  [tx workload-request]
;  )
;
;;; TODO: implement COVID workload creation
;(defn create-covid-workload!
;  [tx {:keys [source sink executor] :as request}]
;  ;; TODO: validation
;  (validate-workflow-permission workflow-launcher-sa-email)
;  ;; TODO: dispatch on source/sink/executor
;  (-> request
;      (add-workload-table! tx)
;      (workloads/load-workload-for-id tx id)))
;
;(comment
;
;
;  (let [workload-request {:creator "ranthony@broadinstitute.org",
;                          :source
;                                     {:name "Terra DataRepo",
;                                      :dataset "sarscov2_illumina_full",
;                                      :table "inputs",
;                                      :snapshot "run_date"},
;                          :executor
;                                    {:name "Terra",
;                                     :workspace
;                                     "pathogen-genomic-surveillance/CDC_Viral_Sequencing",
;                                     :methodConfiguration
;                                     "pathogen-genomic-surveillance/sarscov2_illumina_full",
;                                     :version "3",
;                                     :entity "snapshot",
;                                     :fromSource "importSnapshot"},
;                          :sink
;                                    {:name "Terra Workspace",
;                                     :workspace
;                                     "pathogen-genomic-surveillance/CDC_Viral_Sequencing",
;                                     :entity "flowcell",
;                                     :fromOutputs {:column1 "output1"}},
;                          :labels
;                                    ["project:TestBoston"
;                                     "project:COVID-19 Surveillance"
;                                     "pipeline:sarscov2_illumina_full"],
;                          :watchers ["cloreth@broadinstitute.org"]}]
;
;    )
;
;  (defn add-workload-table!
;    "Use transaction `tx` to add a `CromwellWorkflow` table
;    for a `workload-request` running `workflow-wdl`."
;    [tx workflow-wdl workload-request]
;    (let [{:keys [path release]} workflow-wdl
;          {:keys [pipeline]} workload-request
;          create "CREATE TABLE %s OF CromwellWorkflow (PRIMARY KEY (id))"
;          setter "UPDATE workload SET pipeline = ?::pipeline WHERE id = ?"
;          [{:keys [id]}]
;          (-> workload-request
;              (select-keys [:creator :executor :input :output :project])
;              (update :executor util/de-slashify)
;              (merge (select-keys (wfl/get-the-version) [:commit :version]))
;              (assoc :release release :wdl path :uuid (UUID/randomUUID))
;              (->> (jdbc/insert! tx :workload)))
;          table (format "%s_%09d" pipeline id)]
;      (jdbc/execute!       tx [setter pipeline id])
;      (jdbc/db-do-commands tx [(format create table)])
;      (jdbc/update!        tx :workload {:items table} ["id = ?" id])
;      [id table]))
;)
;
;;; TODO: implement progressive (private) functions inside this update loop
;(defn update-covid-workload!
;  "Use transaction `tx` to batch-update `workload` statuses."
;  [tx {:keys [started finished] :as workload}]
;  (letfn [(update! [{:keys [id] :as workload}]
;            (postgres/batch-update-workflow-statuses! tx workload)
;            (postgres/update-workload-status! tx workload)
;            (workloads/load-workload-for-id tx id))]
;    (if (and started (not finished)) (update! workload) workload)))

(defoverload workloads/create-workload!   pipeline create-covid-workload!)
;(defoverload workloads/start-workload!    pipeline start-covid-workload!)
;(defoverload workloads/update-workload!   pipeline update-covid-workload!)
;(defoverload workloads/stop-workload!     pipeline batch/stop-workload!)
;(defoverload workloads/load-workload-impl pipeline
;  batch/load-batch-workload-impl)

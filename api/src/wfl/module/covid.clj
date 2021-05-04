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
            [wfl.service.rawls :as rawls]
            [wfl.util :as util]
            [wfl.wfl :as wfl])
  (:import (java.time OffsetDateTime)))

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
  "Verify that the `dataset` exists and that the WFL has the necessary permissions to read it"
  [{:keys [name dataset] :as source}]
  (when-not (= (:name source) "Terra DataRepo")
    (throw (ex-info "Unknown Source" {:source source})))
  (try
    (#((datarepo/dataset (:dataset source)) nil))
    (catch Throwable t
      (throw (ex-info "Cannot access Dataset" {:dataset dataset
                                               :cause (.getMessage t)})))))

(defn verify-executor!
  "Verify the method-configuration exists."
  [{:keys [name method_configuration] :as executor}]
  (when-not (= (:name executor) "Terra")
    (throw (ex-info "Unknown Executor" {:executor executor})))
  (when-not (:method_configuration executor)
    (throw (ex-info "Unknown Method Configuration" {:executor executor}))))

(defn verify-sink!
  "Verify that the WFL has access to both firecloud and the `workspace`."
  [{:keys [name workspace] :as sink}]
  (when-not (= (:name sink) "Terra Workspace")
    (throw (ex-info "Unknown Sink" {:sink sink})))
  (try
    (firecloud/get-workspace workspace)
    (catch Throwable t
      (throw (ex-info "Cannot access the workspace" {:workspace workspace
                                                     :cause (.getMessage t)})))))

(defn create-covid-workload!
  [tx {:keys [source executor sink] :as workload-request}]
  (verify-source! source)
  (verify-executor! executor)
  (verify-sink! sink))

(defn start-covid-workload!
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
(defoverload workloads/load-workload-impl pipeline batch/load-batch-workload-impl)

(defmulti peek-queue!
  "Peek the first object from the `queue`, if one exists."
  (fn [queue] (:type queue)))

(defmulti pop-queue!
  "Pop the first object from the `queue`. Throws if none exists."
  (fn [queue] (:type queue)))

;; source operations
(defmulti create-source!
  "Create and write the source to persisted storage"
  (fn [source-request] (:name source-request)))

(defmulti update-source!
  "Update the source."
  (fn [source] (:type source)))

(defmulti load-source!
  "Load the workload `source`."
  (fn [workload] (:source-type workload)))

;; executor operations
(defmulti create-executor!
  "Create and write the executor to persisted storage"
  (fn [executor-request] (:name executor-request)))

(defmulti update-executor!
  "Update the executor with the `source`"
  (fn [source executor] (:type executor)))

(defmulti load-executor!
  "Load the workload `executor`."
  (fn [workload] (:executor-type workload)))

;; sink operations
(defmulti create-sink!
  "Create and write the sink to persisted storage"
  (fn [sink-request] (:name sink-request)))

(defmulti update-sink!
  "Update the sink with the `executor`"
  (fn [executor sink] (:type sink)))

(defmulti load-sink!
  "Load the workload `sink`."
  (fn [workload] (:sink-type workload)))

(defn update-covid-workload
  "Use transaction TX to update WORKLOAD statuses."
  [tx {:keys [started finished] :as workload}]
  (letfn [(update-workload-status [])
          (update! [{:keys [id source executor sink] :as _workload}]
            (-> (update-source! source)
                (update-executor! executor)
                (update-sink! sink))
            (update-workload-status)
            (workloads/load-workload-for-id tx id))]
    (if (and started (not finished)) (update! workload) workload)))

(def tdr-source-type "TerraDataRepoSink")

;; Note I've used a table to implement the queue and I'm deleting the row
;; when it's popped.
;;
;; You could just mark it as :visited or something.
(defn ^:private peek-tdr-source-queue [{:keys [queue] :as _source}]
  (let [query "SELECT * FROM %s ORDER BY id ASC LIMIT 1"]
    (jdbc/with-db-transaction [tx (postgres/wfl-db-config)]
      (first (jdbc/query tx (format query queue))))))

(defn ^:private pop-tdr-source-queue [{:keys [queue] :as source}]
  (if-let [{:keys [id] :as _snapshot} (peek-queue! source)]
    (let [query "DELETE FROM %s WHERE id = ?"]
      (jdbc/with-db-transaction [tx (postgres/wfl-db-config)]
        (jdbc/execute! tx [(format query queue) id])))
    (throw (ex-info "No snapshots in queue" {:source source}))))

;; Create and add new snapshots to the snapshot queue
(defn ^:private update-tdr-source [source]
  (letfn [(find-new-rows       [source now]      [])
          (make-snapshots      [source row-ids])
          (write-snapshots     [source snapshots])
          (update-last-checked [now])]
    (let [now     (OffsetDateTime/now)
          row-ids (find-new-rows source now)]
      (when-not (empty? row-ids)
        (write-snapshots source (make-snapshots source row-ids)))
      (update-last-checked source now))))

(defn ^:private load-tdr-source [{:keys [source-ptr] :as workload}]
  {:type    tdr-source-type
   :queue   nil
   :updated nil})

;; maybe we want to split check-request and create to avoid writing
;; records for bad workload requests?
(defn ^:private create-tdr-source [source-request]
  (letfn [(check-request! [request] (throw (Exception. "Not implemented")))
          (make-record    [request] nil)]
    (check-request! source-request)
    (let [foreign-key (make-record source-request)]
      (load-tdr-source {:source-ptr foreign-key}))))

(defoverload create-source! tdr-source-type create-tdr-source)
(defoverload peek-queue!    tdr-source-type peek-tdr-source-queue)
(defoverload pop-queue!     tdr-source-type pop-tdr-source-queue)
(defoverload update-source! tdr-source-type update-tdr-source)
(defoverload load-source!   tdr-source-type load-tdr-source)

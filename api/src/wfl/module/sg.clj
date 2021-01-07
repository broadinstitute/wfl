(ns wfl.module.sg
  "Handle Somatic Genomes."
  (:require [clojure.data.json :as json]
            [wfl.api.workloads :as workloads]
            [wfl.api.workloads :refer [defoverload]]
            [wfl.jdbc :as jdbc]
            [wfl.module.batch :as batch]
            [wfl.module.wgs :as wgs]
            [wfl.references :as references]
            [wfl.util :as util]
            [wfl.wfl :as wfl])
  (:import [java.time OffsetDateTime]))

(def pipeline "GDCWholeGenomeSomaticSingleSample")

(def ^:private cromwell-label
  "The WDL label applied to Cromwell metadata."
  {(keyword wfl/the-name) pipeline})

(def workflow-wdl
  "The top-level WDL file and its version."
  {:release "b0e3cfef18fc3c4126b7b835ab2b253599a18904"
   :path    "beta-pipelines/broad/somatic/single_sample/wgs/gdc_genome/GDCWholeGenomeSomaticSingleSample.wdl"})

(defn ^:private cromwellify-workflow-inputs [_ {:keys [inputs]}]
  (-> references/gdc-sg-references
      (util/deep-merge inputs)
      (util/prefix-keys pipeline)))

(defn ^:private per-workflow-default-options [{:keys [output]}]
  "Cause workflow outputs to be at `{output}/{pipeline}/{workflow uuid}/{pipeline task}/execution/`."
  {:final_workflow_outputs_dir output})

(defn create-sg-workload!
  [tx {:keys [common items] :as request}]
  (letfn [(nil-if-empty [x] (if (empty? x) nil x))
          (merge-to-json [shared specific]
            (json/write-str (nil-if-empty (util/deep-merge shared specific))))
          (serialize [item id]
            (-> item
                (assoc :id id)
                (update :options #(merge-to-json (:options common) %))
                (update :inputs #(merge-to-json (:inputs common) %))))]
    (let [[id table] (batch/add-workload-table! tx workflow-wdl request)]
      (jdbc/insert-multi! tx table (map serialize items (range)))
      (workloads/load-workload-for-id tx id))))

(defn start-sg-workload! [tx {:keys [items id] :as workload}]
  (letfn [(update-record! [{:keys [id] :as workflow}]
            (let [values (select-keys workflow [:uuid :status :updated])]
              (jdbc/update! tx items values ["id = ?" id])))]
    (let [now (OffsetDateTime/now)
          ;; SG is derivative of WGS and should use precisely the same environments
          env (wgs/get-cromwell-environment workload)
          default-options (util/deep-merge (util/make-options env) (per-workflow-default-options workload))]
      (run! update-record! (batch/submit-workload! workload env workflow-wdl cromwellify-workflow-inputs cromwell-label
                                                   default-options))
      (jdbc/update! tx :workload {:started now} ["id = ?" id]))
    (workloads/load-workload-for-id tx id)))

(defoverload workloads/create-workload! pipeline create-sg-workload!)
(defoverload workloads/start-workload! pipeline start-sg-workload!)
(defoverload workloads/update-workload! pipeline batch/update-workload!)
(defoverload workloads/load-workload-impl pipeline batch/load-batch-workload-impl)
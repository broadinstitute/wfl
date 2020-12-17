(ns wfl.module.sg
  "Handle Somatic Genomes."
  (:require [clojure.data.json :as json]
            [wfl.api.workloads :refer [defoverload]]
            [wfl.api.workloads :as workloads]
            [wfl.jdbc :as jdbc]
            [wfl.module.all :as all]
            [wfl.module.batch :as batch]
            [wfl.util :as util]
            [wfl.wfl :as wfl])
  (:import [java.time OffsetDateTime]))

(def pipeline "GDCWholeGenomeSomaticSingleSample")

(def ^:private cromwell-label
  "The WDL label applied to Cromwell metadata."
  {(keyword wfl/the-name) pipeline})

(def workflow-wdl
  "The top-level WDL file and its version."
  {:release "develop"
   :top     "beta-pipelines/broad/somatic/single_sample/wgs/gdc_genome/GDCWholeGenomeSomaticSingleSample.wdl"})

(def default-inputs
  (let [prefix-vals (fn [s m] (reduce (fn [acc [k v]] (assoc acc k (str s v))) {} m))
        vcf "gs://pgdac-gdc/dbsnp_144.hg38.vcf"
        vd1 "gs://getzlab-workflows-reference_files-oa/hg38/gdc/GRCh38.d1.vd1"]
    (merge
     (util/prefix-keys
      (prefix-vals vcf
                   {:dbsnp_vcf       ".gz"
                    :dbsnp_vcf_index ".gz.tbi"})
      "gatk_baserecalibrator")
     (prefix-vals vd1
                  {:ref_amb   ".fa.amb"
                   :ref_ann   ".fa.ann"
                   :ref_bwt   ".fa.bwt"
                   :ref_dict  ".dict"
                   :ref_fai   ".fa.fai"
                   :ref_fasta ".fa"
                   :ref_pac   ".fa.pac"
                   :ref_sa    ".fa.sa"}))))

;; visible for testing
(defn get-cromwell-environment [{:keys [cromwell]}]
  (let [cromwell (all/de-slashify cromwell)
        envs     (all/cromwell-environments #{:gotc-dev :gotc-prod} cromwell)]
    (when (not= 1 (count envs))
      (throw (ex-info "no unique environment matching Cromwell URL."
                      {:cromwell     cromwell
                       :environments envs})))
    (first envs)))

(defn ^:private cromwellify-workflow-inputs [_ {:keys [inputs]}]
  (-> references/gdc-sg-references
      (util/deep-merge inputs)
      (util/prefix-keys pipeline)))

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
          env (get-cromwell-environment workload)]
      (run! update-record! (batch/submit-workload! workload env workflow-wdl cromwellify-workflow-inputs cromwell-label))
      (jdbc/update! tx :workload {:started now} ["id = ?" id]))
    (workloads/load-workload-for-id tx id)))

(defoverload workloads/create-workload! pipeline create-sg-workload!)
(defoverload workloads/start-workload! pipeline start-sg-workload!)
(defoverload workloads/load-workload-impl pipeline batch/load-batch-workload-impl)

(ns wfl.module.sg
  "Handle Somatic Genomes."
  (:require [clojure.data.json :as json]
            [clojure.string :as str]
            [wfl.api.workloads :as workloads]
            [wfl.api.workloads :refer [defoverload]]
            [wfl.jdbc :as jdbc]
            [wfl.module.batch :as batch]
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

(def ^:private known-cromwells
  ["https://cromwell-gotc-auth.gotc-dev.broadinstitute.org"
   "https://cromwell-gotc-auth.gotc-prod.broadinstitute.org"])

(defn ^:private is-known-cromwell-url?
  [url]
  (if-let [known-url (->> url
                          util/de-slashify
                          ((set known-cromwells)))]
    known-url
    (throw (ex-info "Unknown Cromwell URL provided."
                    {:cromwell url
                     :known-cromwells known-cromwells}))))

(def ^:private inputs+options
  [{:labels                    [:data_type
                                :project
                                :regulatory_designation
                                :sample_name
                                :version]
    :google
    {:jes_roots ["gs://broad-gotc-dev-cromwell-execution"]
     :noAddress false
     :projects  ["broad-exomes-dev1"]}
    :server
    {:project "broad-gotc-dev"
     :vault   "secret/dsde/gotc/dev/zero"}
    :google_account_vault_path "secret/dsde/gotc/dev/picard/picard-account.pem"
    :vault_token_path          "gs://broad-dsp-gotc-dev-tokens/picardsa.token"}
   (let [prefix   "broad-realign-"
         projects (map (partial str prefix "execution0") (range 1 6))
         buckets  (map (partial str prefix "short-execution") (range 1 11))
         roots    (map (partial format "gs://%s/") buckets)]
     {:cromwell                  {:labels            [:data_type
                                                      :project
                                                      :regulatory_designation
                                                      :sample_name
                                                      :version]
                                  :monitoring_script "gs://broad-gotc-prod-cromwell-monitoring/monitoring.sh"
                                  :url               "https://cromwell-gotc-auth.gotc-prod.broadinstitute.org"}
      :google
      {:jes_roots (vec roots)
       :noAddress false
       :projects  (vec projects)}
      :google_account_vault_path "secret/dsde/gotc/prod/picard/picard-account.pem"
      :vault_token_path          "gs://broad-dsp-gotc-prod-tokens/picardsa.token"})])

(defn ^:private cromwell->inputs+options
  "Map cromwell URL to workflow inputs and options for submitting an Whole Genome SG workflow."
  [url]
  ((zipmap known-cromwells inputs+options) (util/de-slashify url)))

;; visible for testing
(defn make-workflow-options
  "Make workflow options to run the workflow in Cromwell URL."
  [url]
  (letfn [(maybe [m k v] (if-some [kv (k v)] (assoc m k kv) m))]
    (let [gcr   "us.gcr.io"
          repo  "broad-gotc-prod"
          image "genomes-in-the-cloud:2.4.3-1564508330"
          {:keys [cromwell google]} (cromwell->inputs+options url)
          {:keys [projects jes_roots noAddress]} google]
      (-> {:backend         "PAPIv2"
           :google_project  (rand-nth projects)
           :jes_gcs_root    (rand-nth jes_roots)
           :read_from_cache true
           :write_to_cache  true
           :default_runtime_attributes
           {:docker (str/join "/" [gcr repo image])
            :zones  util/google-cloud-zones
            :maxRetries 1}}
          (maybe :monitoring_script cromwell)
          (maybe :noAddress noAddress)))))

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

(defn start-sg-workload! [tx {:keys [items id executor] :as workload}]
  (letfn [(update-record! [{:keys [id] :as workflow}]
            (let [values (select-keys workflow [:uuid :status :updated])]
              (jdbc/update! tx items values ["id = ?" id])))]
    (let [now (OffsetDateTime/now)
          ;; SG is derivative of WGS and should use precisely the same environments
          executor (is-known-cromwell-url? executor)
          default-options (util/deep-merge (make-workflow-options executor) (per-workflow-default-options workload))]
      (run! update-record! (batch/submit-workload! workload executor workflow-wdl cromwellify-workflow-inputs cromwell-label
                                                   default-options))
      (jdbc/update! tx :workload {:started now} ["id = ?" id]))
    (workloads/load-workload-for-id tx id)))

(defoverload workloads/create-workload! pipeline create-sg-workload!)
(defoverload workloads/start-workload! pipeline start-sg-workload!)
(defoverload workloads/update-workload! pipeline batch/update-workload!)
(defoverload workloads/load-workload-impl pipeline batch/load-batch-workload-impl)

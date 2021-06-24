(ns wfl.module.copyfile
  "A dummy module for smoke testing wfl/cromwell auth."
  (:require [clojure.data.json    :as json]
            [clojure.spec.alpha   :as s]
            [clojure.string       :as str]
            [wfl.api.workloads    :as workloads :refer [defoverload]]
            [wfl.jdbc             :as jdbc]
            [wfl.module.batch     :as batch]
            [wfl.service.cromwell :as cromwell]
            [wfl.util             :as util])
  (:import [java.time OffsetDateTime]))

(def pipeline "copyfile")

;; specs
(s/def ::workflow-inputs (s/keys :req-un [::dst ::src]))
(s/def ::dst string?)
(s/def ::src string?)

(def workflow-wdl
  {:repo    "wfl"
   :release "v0.4.2"
   :path    "api/resources/wdl/copyfile.wdl"})

(defn ^:private submit-workflow
  "Submit WORKFLOW to Cromwell URL with OPTIONS and LABELS."
  [url inputs options labels]
  (cromwell/submit-workflow
   url
   workflow-wdl
   (util/prefix-keys inputs (str pipeline "."))
   options
   labels))

(def ^:private known-cromwells
  ["https://cromwell-gotc-auth.gotc-dev.broadinstitute.org"
   "https://cromwell-gotc-auth.gotc-prod.broadinstitute.org"])

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
     {:labels            [:data_type
                          :project
                          :regulatory_designation
                          :sample_name
                          :version]
      :google
      {:jes_roots (vec roots)
       :noAddress false
       :projects  (vec projects)}
      :google_account_vault_path "secret/dsde/gotc/prod/picard/picard-account.pem"
      :vault_token_path          "gs://broad-dsp-gotc-prod-tokens/picardsa.token"})])

(defn ^:private is-known-cromwell-url?
  [url]
  (if-let [known-url (->> url
                          util/de-slashify
                          ((set known-cromwells)))]
    known-url
    (throw (ex-info "Unknown Cromwell URL provided."
                    {:cromwell url
                     :known-cromwells known-cromwells}))))

(defn ^:private cromwell->inputs+options
  "Map cromwell URL to workflow inputs and options for submitting an Whole Genome workflow."
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

(defn create-copyfile-workload!
  "Use transaction TX to add the workload described by REQUEST."
  [tx {:keys [items common] :as request}]
  (letfn [(merge-to-json [shared specific]
            (json/write-str (not-empty (util/deep-merge shared specific))))
          (serialize [workflow id]
            (-> workflow
                (assoc :id id)
                (update :inputs #(merge-to-json (:inputs common) %))
                (update :options #(merge-to-json (:options common) %))))]
    (let [[id table] (batch/add-workload-table! tx workflow-wdl request)]
      (jdbc/insert-multi! tx table (map serialize items (range)))
      (workloads/load-workload-for-id tx id))))

;; TODO: move the URL validation up to workload creation
;;
(defn start-copyfile-workload!
  "Use transaction TX to start _WORKLOAD."
  [tx {:keys [items uuid executor] :as workload}]
  (let [executor        (is-known-cromwell-url? executor)
        default-options (make-workflow-options executor)]
    (letfn [(submit! [{:keys [id inputs options]}]
              [id (submit-workflow executor inputs
                                   (util/deep-merge default-options options)
                                   {:workload uuid})])
            (update! [tx [id uuid]]
              (jdbc/update! tx items
                            {:updated (OffsetDateTime/now) :uuid uuid :status "Submitted"}
                            ["id = ?" id]))]
      (run! (comp (partial update! tx) submit!) (workloads/workflows tx workload))
      (jdbc/update! tx :workload
                    {:started (OffsetDateTime/now)} ["uuid = ?" uuid]))))

(defoverload workloads/create-workload! pipeline create-copyfile-workload!)

(defmethod workloads/start-workload!
  pipeline
  [tx {:keys [id] :as workload}]
  (do
    (start-copyfile-workload! tx workload)
    (workloads/load-workload-for-id tx id)))

(defoverload workloads/update-workload!   pipeline batch/update-workload!)
(defoverload workloads/stop-workload!     pipeline batch/stop-workload!)
(defoverload workloads/load-workload-impl pipeline batch/load-batch-workload-impl)
(defoverload workloads/workflows          pipeline batch/workflows)
(defoverload workloads/to-edn             pipeline batch/workload-to-edn)

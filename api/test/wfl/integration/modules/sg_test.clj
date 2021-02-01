(ns wfl.integration.modules.sg-test
  (:require [clojure.pprint :refer [pprint]]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing use-fixtures]]
            [wfl.environments :as env]
            [wfl.module.batch :as batch]
            [wfl.module.sg :as sg]
            [wfl.service.clio :as clio]
            [wfl.service.gcs :as gcs]
            [wfl.service.cromwell :as cromwell]
            [wfl.tools.endpoints :as endpoints]
            [wfl.tools.fixtures :as fixtures]
            [wfl.tools.workloads :as workloads]
            [wfl.util :as util :refer [absent? make-options]])
  (:import [java.time OffsetDateTime]
           [java.util UUID]))

(use-fixtures :once fixtures/temporary-postgresql-database)

(def ^:private the-uuids (repeatedly #(str (UUID/randomUUID))))

(defn ^:private make-sg-workload-request
  []
  (-> (UUID/randomUUID)
      workloads/sg-workload-request
      (assoc :creator (:email @endpoints/userinfo))))

(defn mock-submit-workload
  [{:keys [workflows]} _ _ _ _ _]
  (let [now (OffsetDateTime/now)]
    (letfn [(submit [workflow uuid] (assoc workflow
                                           :status "Submitted"
                                           :updated now
                                           :uuid    uuid))]
      (map submit workflows the-uuids))))

(deftest test-create-workload!
  (letfn [(verify-workflow [workflow]
            (is (absent? workflow :uuid))
            (is (absent? workflow :status))
            (is (absent? workflow :updated)))
          (go! [workload-request]
            (let [workload (workloads/create-workload! workload-request)]
              (is (:created workload))
              (is (absent? workload :started))
              (is (absent? workload :finished))
              (run! verify-workflow (:workflows workload))))]
    (testing "single-sample workload-request"
      (go! (make-sg-workload-request)))))

(deftest test-update-unstarted
  (let [workload (-> (make-sg-workload-request)
                     workloads/create-workload!
                     workloads/update-workload!)]
    (is (seq  (:workflows workload)))
    (is (nil? (:finished  workload)))
    (is (nil? (:submitted workload)))))

(deftest test-create-workload-with-common-inputs
  (let [expected {:biobambam_bamtofastq.max_retries 2
                  :ref_pac  "gs://fake-location/GRCh38.d1.vd1.fa.pac"}]
    (letfn [(ok [inputs]
              (is (= expected (select-keys inputs (keys expected)))))]
      (run! ok (-> (make-sg-workload-request)
                   (assoc-in [:common :inputs] expected)
                   workloads/create-workload! :workflows
                   (->> (map :inputs)))))))

(deftest test-start-workload!
  (letfn [(go! [workflow]
            (is (:uuid workflow))
            (is (:status workflow))
            (is (:updated workflow)))]
    (with-redefs-fn {#'batch/submit-workload! mock-submit-workload}
      #(-> (make-sg-workload-request)
           workloads/create-workload!
           workloads/start-workload!
           (as-> workload
                 (is (:started workload))
             (run! go! (:workflows workload)))))))

(deftest test-hidden-inputs
  (testing "google_account_vault_path and vault_token_path are not in inputs"
    (letfn [(go! [inputs]
              (is (absent? inputs :vault_token_path))
              (is (absent? inputs :google_account_vault_path)))]
      (->> (make-sg-workload-request)
           workloads/create-workload!
           :workflows
           (run! (comp go! :inputs))))))

(deftest test-create-empty-workload
  (is (->> {:executor (get-in env/stuff [:wgs-dev :cromwell :url])
            :output   "gs://broad-gotc-dev-wfl-ptc-test-outputs/sg-test-output/"
            :pipeline sg/pipeline
            :project  (format "(Test) %s" @workloads/git-branch)
            :creator  (:email @endpoints/userinfo)}
           workloads/execute-workload!
           workloads/update-workload!
           :finished)))

(deftest test-submitted-workflow-inputs
  (let [prefix (str sg/pipeline ".")]
    (letfn [(overmap   [m] (-> m
                               (assoc-in [:inputs :overwritten]     true)
                               (assoc-in [:inputs :supports_inputs] true)))
            (unprefix  [k] (keyword (util/unprefix (name k) prefix)))
            (prefixed? [k] (str/starts-with? (name k) prefix))
            (submit [inputs]
              (is (every? prefixed? (keys inputs)))
              (let [in (zipmap (map unprefix (keys inputs)) (vals inputs))]
                (is (:overwritten            in))
                (is (:supports_common_inputs in))
                (is (:supports_inputs        in))
                (UUID/randomUUID)))
            (verify-submitted-inputs [_ _ inputs _ _] (map submit inputs))]
      (with-redefs-fn {#'cromwell/submit-workflows verify-submitted-inputs}
        #(-> (make-sg-workload-request)
             (assoc-in [:common :inputs] {:overwritten            false
                                          :supports_common_inputs true})
             (update :items (partial map overmap))
             workloads/execute-workload!)))))

(deftest test-workflow-options
  (letfn [(overmap [m] (-> m
                           (assoc-in [:options :overwritten]      true)
                           (assoc-in [:options :supports_options] true)))
          (verify-workflow-options [options]
            (is (:overwritten             options))
            (is (:supports_common_options options))
            (is (:supports_options        options)))
          (verify-submitted-options [env _ inputs options _]
            (verify-workflow-options options)
            (let [defaults (util/make-options env)]
              (is (= defaults (select-keys options (keys defaults))))
              (map (fn [_] (UUID/randomUUID)) inputs)))]
    (with-redefs-fn {#'cromwell/submit-workflows verify-submitted-options}
      #(-> (make-sg-workload-request)
           (assoc-in [:common :options] {:overwritten             false
                                         :supports_common_options true})
           (update :items (partial map overmap))
           workloads/execute-workload! :workflows
           (->> (map (comp verify-workflow-options :options)))))))

(deftest test-empty-workflow-options
  (letfn [(go! [workflow] (is (absent? workflow :options)))
          (empty-options [m] (assoc m :options {}))]
    (run! go! (-> (make-sg-workload-request)
                  (assoc-in [:common :options] {})
                  (update :items (partial map empty-options))
                  workloads/create-workload! :workflows))))

(defn ^:private ensure-clio-cram
  "Ensure there is a CRAM record in Clio suitable for test."
  []
  (let [version 23
        project (str "G96830" \- (UUID/randomUUID))
        NA12878 (str/join "/" ["gs://broad-gotc-dev-wfl-sg-test-inputs"
                               "pipeline"
                               project
                               "NA12878"
                               (str \v version) "NA12878"])
        path    (partial str NA12878)
        query   {:billing_project        "hornet-nest"
                 :cram_md5               "0cfd2e0890f45e5f836b7a82edb3776b"
                 :cram_path              (path ".cram")
                 :cram_size              19512619343
                 :data_type              "WGS"
                 :document_status        "Normal"
                 :location               "GCP"
                 :notes                  "Blame tbl for SG test."
                 :pipeline_version       "f1c7883"
                 :project                project
                 :readgroup_md5          "a128cbbe435e12a8959199a8bde5541c"
                 :regulatory_designation "RESEARCH_ONLY"
                 :sample_alias           "NA12878"
                 :version                version
                 :workspace_name         "bike-of-hornets"}
        crams   (clio/query-cram query)]
    (when (> (count crams) 1)
      (throw (ex-info "More than 1 Clio CRAM record"
                      (with-out-str (pprint crams)))))
    (or (first crams)
        (clio/add-cram
         (merge query
                {:crai_path                  (path ".cram.crai")
                 :cromwell_id                (str (UUID/randomUUID))
                 :insert_size_histogram_path (path ".insert_size_histogram.pdf")
                 :insert_size_metrics_path   (path ".insert_size_metrics")
                 :workflow_start_date        (str (OffsetDateTime/now))})))
    (first (clio/query-cram query))))

(def ^:private the-clio-cram-record (delay (ensure-clio-cram)))

(defn ^:private mock-cromwell-query
  "Update `status` of all workflows to `Succeeded`."
  [_environment _params]
  (let [{:keys [items]} (make-sg-workload-request)]
    (map (fn [id] {:id id :status "Succeeded"})
         (take (count items) the-uuids))))

(defn ^:private mock-cromwell-submit-workflows
  [_environment _wdl inputs _options _labels]
  (take (count inputs) the-uuids))

(def ^:private bam-suffixes
  "Map Clio BAM record fields to expected file suffixes."
  {:bai_path                 ".bai"
   :bam_path                 ".bam"
   :insert_size_metrics_path ".insert_size_metrics"})

(defn ^:private expect-clio-bams
  "Make the Clio BAM records expected from `workload`."
  [{:keys [output pipeline workflows] :as workload}]
  (letfn [(make [{:keys [inputs uuid] :as workflow}]
            (let [{:keys [input_cram sample_name]} inputs
                  prefix (partial str (str/join "/" [output
                                                     pipeline
                                                     uuid
                                                     pipeline
                                                     "execution"
                                                     sample_name]))]
              (zipmap (keys bam-suffixes) (map prefix (vals bam-suffixes)))))]
    (let [from-cram (select-keys @the-clio-cram-record [:billing_project
                                                        :data_type
                                                        :document_status
                                                        :location
                                                        :notes
                                                        :project
                                                        :sample_alias
                                                        :version])]
      (map (partial merge from-cram) (map make workflows)))))

;; The files are all just bogus copies of the README now.
;;
(defn ^:private make-bam-outputs
  "Make the BAM outputs expected from a successful `workload`."
  [{:keys [output pipeline workflows] :as workload}]
  (let [readme (str/join "/" ["gs://broad-gotc-dev-wfl-sg-test-inputs"
                              pipeline
                              "README.txt"])
        copy!  (partial gcs/copy-object readme)]
    (->> workload expect-clio-bams
         (mapcat (apply juxt (keys bam-suffixes)))
         (run! copy!)))
  workload)

(deftest test-clio-updates
  (testing "Clio updated after workflows finish."
    (let [where [:items 0 :inputs]
          {:keys [cram_path project sample_alias]} @the-clio-cram-record]
      (with-redefs-fn {#'cromwell/submit-workflows mock-cromwell-submit-workflows
                       #'cromwell/query            mock-cromwell-query}
        #(-> (make-sg-workload-request)
             (update :items (comp vector first))
             (assoc-in (conj where :input_cram)  cram_path)
             (assoc-in (conj where :project)     project)
             (assoc-in (conj where :sample_name) sample_alias)
             workloads/create-workload!
             workloads/start-workload!
             workloads/update-workload! ; Make status "Succeeded".
             make-bam-outputs
             workloads/update-workload! ; Register outputs with Clio.
             expect-clio-bams
             (as-> expected
                   (let [query (-> expected first (select-keys [:bam_path]))]
                     (is (= expected (clio/query-bam query))))))))))

(comment
  (clojure.test/test-vars [#'test-clio-updates]))

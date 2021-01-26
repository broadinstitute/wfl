(ns wfl.integration.modules.sg-test
  (:require [clojure.pprint :refer [pprint]]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing use-fixtures]]
            [wfl.environments :as env]
            [wfl.module.batch :as batch]
            [wfl.module.sg :as sg]
            [wfl.service.clio :as clio]
            [wfl.service.cromwell :as cromwell]
            [wfl.tools.endpoints :as endpoints]
            [wfl.tools.fixtures :as fixtures]
            [wfl.tools.workloads :as workloads]
            [wfl.util :as util :refer [absent? make-options]])
  (:import [java.time OffsetDateTime]
           [java.util UUID]))

(use-fixtures :once fixtures/temporary-postgresql-database)

(defn ^:private make-sg-workload-request
  []
  (-> (UUID/randomUUID)
      workloads/sg-workload-request
      (assoc :creator (:email @endpoints/userinfo))))

(defn mock-submit-workload [{:keys [workflows]} _ _ _ _ _]
  (let [now       (OffsetDateTime/now)
        do-submit #(assoc % :uuid (UUID/randomUUID)
                          :status "Submitted"
                          :updated now)]
    (map do-submit workflows)))

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
        NA12878 (str/join "/" ["gs://broad-gotc-dev-storage" "pipeline"
                               "G96830" "NA12878" (str "v" version) "NA12878"])
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
                 :project                "G96830"
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
                 :workflow_start_date        (str (OffsetDateTime/now))}))
        (clio/query-cram query))))

(deftest test-clio-updates
  (let [where                            [:items 0 :inputs]
        {:keys [cram_path sample_alias]} (ensure-clio-cram)]
    (with-redefs-fn {#'batch/submit-workload! mock-submit-workload}
      #(-> (make-sg-workload-request)
           (update :items (comp vector first))
           (assoc-in (conj where :input_cram)  cram_path)
           (assoc-in (conj where :sample_name) sample_alias)
           workloads/create-workload!
           workloads/start-workload!
           workloads/update-workload!))))

(comment
  (clojure.test/test-vars [#'test-clio-updates])
  (test-clio-updates)
  (make-sg-workload-request)
  "2021-01-21T17:59:20.203558-05:00"
  "2017-09-19T00:04:30-04:00"
  (ensure-clio-cram)
  (clio/query-cram {:cromwell_id "4f3d3307-aaad-4373-acb2-1c5a7a068110"})
  {:started #inst "2021-01-26T19:14:06.746478000-00:00",
   :creator "tbl@broadinstitute.org",
   :pipeline "GDCWholeGenomeSomaticSingleSample",
   :release "b0e3cfef18fc3c4126b7b835ab2b253599a18904",
   :created #inst "2021-01-26T19:14:06.727552000-00:00",
   :output
   "gs://broad-gotc-dev-storage/GDCWholeGenomeSomaticSingleSample/4366635d-9eec-40d7-b02f-c848dcd0e41d",
   :workflows
   [{:id 0,
     :status "Submitted",
     :updated #inst "2021-01-26T19:14:06.746984000-00:00",
     :uuid "b5a59e98-0f76-4431-83ac-ecf2102ce790",
     :inputs
     {:cram_ref_fasta "gs://gcp-public-data--broad-references/hg38/v0/Homo_sapiens_assembly38.fasta",
      :cram_ref_fasta_index "gs://gcp-public-data--broad-references/hg38/v0/Homo_sapiens_assembly38.fasta.fai",
      :input_cram "gs://broad-gotc-dev-storage/pipeline/G96830/NA12878/v23/NA12878.cram",
      :sample_name "NA12878"}}],
   :project "(Test) tbl/GH-1091-track-sg",
   :id 1,
   :commit "834d2810a0dd8c9c6f6cedec8e0edc18bd2f9ed3",
   :wdl "beta-pipelines/broad/somatic/single_sample/wgs/gdc_genome/GDCWholeGenomeSomaticSingleSample.wdl",
   :uuid "f4f38a38-b358-4a38-b134-fd41fd5e4b47",
   :executor "https://cromwell-gotc-auth.gotc-dev.broadinstitute.org",
   :items "GDCWholeGenomeSomaticSingleSample_000000001",
   :version "0.6.0"}
  )

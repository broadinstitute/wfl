(ns wfl.integration.modules.sg-test
  (:require [clojure.pprint :refer [pprint]]
            [clojure.string :as str]
            [clojure.test :refer [deftest testing is] :as clj-test]
            [wfl.environments :as env]
            [wfl.module.batch :as batch]
            [wfl.module.sg :as sg]
            [wfl.service.clio :as clio]
            [wfl.service.cromwell :refer [wait-for-workflow-complete
                                          submit-workflows]]
            [wfl.tools.endpoints :as endpoints]
            [wfl.tools.fixtures :as fixtures]
            [wfl.tools.workloads :as workloads]
            [wfl.util :as util :refer [absent? make-options]])
  (:import [java.time OffsetDateTime]
           [java.util UUID]))

(clj-test/use-fixtures :once fixtures/temporary-postgresql-database)

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
      (with-redefs-fn {#'submit-workflows verify-submitted-inputs}
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
    (with-redefs-fn {#'submit-workflows verify-submitted-options}
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
  (let [version 470
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
        (let [md
              (merge
               query
               {:crai_path                  (path ".cram.crai")
                :cromwell_id                (str (UUID/randomUUID))
                :insert_size_histogram_path (path ".insert_size_histogram.pdf")
                :insert_size_metrics_path   (path ".insert_size_metrics")
                :workflow_start_date        (str (OffsetDateTime/now))})]
          (clio/add-cram md)
          (clio/query-cram md)))))

(deftest test-clio-updates
  (let [{:keys [cram_path sample_alias]} (ensure-clio-cram)
        input                            {:input_cram  cram_path
                                          :sample_name sample_alias}]
    (-> (make-sg-workload-request)
        (assoc-in [:items 0 :input_cram]))))

(comment
  (test-clio-updates)
  (make-sg-workload-request)
  "2021-01-21T17:59:20.203558-05:00"
  "2017-09-19T00:04:30-04:00"
  (ensure-clio-cram)
  (clio/add-cram
   {:billing_project "hornet-nest"
    :crai_path "gs://broad-gotc-dev-storage/pipeline/G96830/NA12878/v470/NA12878.cram.crai"
    :cram_md5 "0cfd2e0890f45e5f836b7a82edb3776b"
    :cram_path "gs://broad-gotc-dev-storage/pipeline/G96830/NA12878/v470/NA12878.cram"
    :cram_size 19512619343
    :cromwell_id "50726371-6d94-468f-a204-c01512c8737a"
    :data_type "WGS"
    :document_status "Normal"
    :insert_size_histogram_path "gs://broad-gotc-dev-storage/pipeline/G96830/NA12878/v470/NA12878.insert_size_histogram.pdf"
    :insert_size_metrics_path "gs://broad-gotc-dev-storage/pipeline/G96830/NA12878/v470/NA12878.insert_size_metrics"
    :location "GCP"
    :notes "Blame tbl for SG test."
    :pipeline_version "f1c7883"
    :project "G96830"
    :readgroup_md5 "a128cbbe435e12a8959199a8bde5541c"
    :regulatory_designation "RESEARCH_ONLY"
    :sample_alias "NA12878"
    :version 1
    :workflow_start_date "2021-01-21T18:24:45.284423-05:00"
    :workspace_name "bike-of-hornets"})
  (clio/query-cram
   {:cromwell_id "50726371-6d94-468f-a204-c01512c8737a"})
  )

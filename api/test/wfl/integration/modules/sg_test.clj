(ns wfl.integration.modules.sg-test
  (:require [clojure.string                 :as str]
            [clojure.test                   :refer :all]
            [wfl.integration.modules.shared :as shared]
            [wfl.jdbc                       :as jdbc]
            [wfl.module.batch               :as batch]
            [wfl.module.sg                  :as sg]
            [wfl.service.clio               :as clio]
            [wfl.service.cromwell           :as cromwell]
            [wfl.service.google.storage     :as gcs]
            [wfl.tools.fixtures             :as fixtures]
            [wfl.tools.workloads            :as workloads]
            [wfl.util                       :as util :refer [absent?]])
  (:import [java.time OffsetDateTime]
           [java.util UUID]))

(use-fixtures :once fixtures/temporary-postgresql-database)

(def ^:private the-uuids (repeatedly #(str (UUID/randomUUID))))

(defn the-sg-workload-request
  []
  "A request suitable when all external services are mocked."
  {:executor @workloads/cromwell-url
   :output   "gs://broad-gotc-dev-wfl-sg-test-outputs"
   :pipeline "GDCWholeGenomeSomaticSingleSample"
   :project  @workloads/project
   :items
   [{:inputs
     {:base_file_name "NA12878"
      :contamination_vcf
      "gs://gatk-best-practices/somatic-hg38/small_exac_common_3.hg38.vcf.gz"
      :contamination_vcf_index
      "gs://gatk-best-practices/somatic-hg38/small_exac_common_3.hg38.vcf.gz.tbi"
      :cram_ref_fasta
      "gs://gcp-public-data--broad-references/hg38/v0/Homo_sapiens_assembly38.fasta"
      :cram_ref_fasta_index
      "gs://gcp-public-data--broad-references/hg38/v0/Homo_sapiens_assembly38.fasta.fai"
      :dbsnp_vcf
      "gs://broad-gotc-dev-storage/temp_references/gdc/dbsnp_144.hg38.vcf.gz"
      :dbsnp_vcf_index
      "gs://broad-gotc-dev-storage/temp_references/gdc/dbsnp_144.hg38.vcf.gz.tbi"
      :input_cram
      "gs://broad-gotc-dev-wfl-sg-test-inputs/pipeline/G96830/NA12878/v23/NA12878.cram"}}]
   :creator @workloads/email})

(defn mock-submit-workload
  [_ workflows _ _ _ _ _]
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
              (run! verify-workflow (workloads/workflows workload))))]
    (testing "single-sample workload-request"
      (go! (the-sg-workload-request)))))

(deftest test-create-workload-with-common-inputs
  (let [expected {:biobambam_bamtofastq.max_retries 2
                  :ref_pac  "gs://fake-location/GRCh38.d1.vd1.fa.pac"}]
    (letfn [(ok [inputs]
              (is (= expected (select-keys inputs (keys expected)))))]
      (run! ok (-> (the-sg-workload-request)
                   (assoc-in [:common :inputs] expected)
                   workloads/create-workload!
                   workloads/workflows
                   (->> (map :inputs)))))))

(deftest test-start-workload!
  (letfn [(go! [workflow]
            (is (:uuid workflow))
            (is (:status workflow))
            (is (:updated workflow)))]
    (with-redefs-fn {#'batch/submit-workload! mock-submit-workload}
      #(let [workload (-> (the-sg-workload-request)
                          workloads/create-workload!
                          workloads/start-workload!)]
         (is (:started workload))
         (run! go! (workloads/workflows workload))))))

(deftest test-hidden-inputs
  (testing "google_account_vault_path and vault_token_path are not in inputs"
    (letfn [(go! [inputs]
              (is (absent? inputs :vault_token_path))
              (is (absent? inputs :google_account_vault_path)))]
      (->> (the-sg-workload-request)
           workloads/create-workload!
           workloads/workflows
           (run! (comp go! :inputs))))))

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
        #(-> (the-sg-workload-request)
             (assoc-in [:common :inputs] {:overwritten            false
                                          :supports_common_inputs true})
             (update :items (partial map overmap))
             workloads/execute-workload!)))))

(deftest test-workflow-options
  (let [{:keys [output] :as request} (the-sg-workload-request)]
    (letfn [(overmap [m] (-> m
                             (assoc-in [:options :overwritten]      true)
                             (assoc-in [:options :supports_options] true)))
            (verify-workflow-options [options]
              (is (:overwritten             options))
              (is (:supports_common_options options))
              (is (:supports_options options)))
            (verify-submitted-options [url _ inputs options _]
              (verify-workflow-options options)
              (let [defaults (sg/make-workflow-options url output)]
                (is (= defaults (select-keys options (keys defaults))))
                (map (fn [_] (UUID/randomUUID)) inputs)))]
      (with-redefs-fn {#'cromwell/submit-workflows verify-submitted-options}
        #(-> request
             (assoc-in [:common :options] {:overwritten             false
                                           :supports_common_options true})
             (update :items (partial map overmap))
             workloads/execute-workload!
             workloads/workflows
             (->> (map (comp verify-workflow-options :options))))))))

(deftest test-empty-workflow-options
  (letfn [(go! [workflow] (is (absent? workflow :options)))
          (empty-options [m] (assoc m :options {}))]
    (run! go! (-> (the-sg-workload-request)
                  (assoc-in [:common :options] {})
                  (update :items (partial map empty-options))
                  workloads/create-workload!
                  workloads/workflows))))

(defn ^:private mock-clio-add-bam-found
  "Fail because a `_clio` BAM record already exists for `_md`"
  [_clio _md]
  (is false))

(defn ^:private mock-clio-add-bam-missing
  "Add a missing `_clio` BAM record with metadata `md`."
  [_clio md]
  (is md)
  (letfn [(ok? [v] (or (integer? v) (and (string? v) (seq v))))]
    (is (every? ok? ((apply juxt clio/add-keys) md))))
  "-MRu7X3zEzoGeFAVSF-J")

(defn ^:private mock-clio-failed
  "Fail when `_clio` called with metadata `_md`."
  [_clio _md]
  (is false))

(defn ^:private mock-clio-query-bam-found
  "Return a `_clio` BAM record with metadata `_md`."
  [_clio {:keys [bam_path] :as _md}]
  [{:bai_path (str/replace bam_path ".bam" ".bai")
    :bam_path bam_path
    :billing_project "hornet-nest"
    :data_type "WGS"
    :document_status "Normal"
    :insert_size_metrics_path
    (str/replace bam_path ".bam" ".insert_size_metrics")
    :location "GCP"
    :notes "Blame tbl for SG test."
    :project "G96830"
    :sample_alias "NA12878"
    :version 23}])

(defn ^:private mock-clio-query-bam-missing
  "Return an empty `_clio` response for query metadata `_md`."
  [_clio _md]
  [])

(defn ^:private mock-clio-query-cram-found
  "Return a `_clio` CRAM record with metadata `_md`."
  [_clio {:keys [cram_path] :as _md}]
  [{:billing_project "hornet-nest"
    :crai_path (str cram_path ".crai")
    :cram_md5 "0cfd2e0890f45e5f836b7a82edb3776b"
    :cram_path cram_path
    :cram_size 19512619343
    :cromwell_id "3586c013-4fbb-4997-a3ec-14a021e50d2d"
    :data_type "WGS"
    :document_status "Normal"
    :insert_size_histogram_path
    (str/replace cram_path ".cram" ".insert_size_histogram.pdf")
    :insert_size_metrics_path
    (str/replace cram_path ".cram" ".insert_size_metrics")
    :location "GCP"
    :notes "Blame tbl for SG test."
    :pipeline_version "f1c7883"
    :project "G96830"
    :readgroup_md5 "a128cbbe435e12a8959199a8bde5541c"
    :regulatory_designation "RESEARCH_ONLY"
    :sample_alias "NA12878"
    :version 23
    :workflow_start_date "2021-01-27T19:33:45.746213-05:00"
    :workspace_name "bike-of-hornets"}])

(defn ^:private mock-cromwell-metadata-failed
  "Return enough metadata for Cromwell workflow `id` to fail."
  [_url id]
  (let [now    (OffsetDateTime/now)]
    {:end    now
     :id     id
     :inputs {:input_cram (str/join "/" ["gs://broad-gotc-test-storage"
                                         "germline_single_sample/wgs"
                                         "plumbing/truth/develop"
                                         "G96830.NA12878"
                                         "NA12878_PLUMBING.cram"])}
     :start        now
     :status       "Failed"
     :submission   now
     :workflowName sg/pipeline}))

(defn ^:private mock-cromwell-metadata-succeeded
  "Return enough metadata for Cromwell workflow `id` to succeed."
  [_url id]
  (let [now    (str (OffsetDateTime/now))
        prefix (str/join "/" ["gs://broad-gotc-dev-wfl-sg-test-outputs"
                              "SOME-UUID"
                              "GDCWholeGenomeSomaticSingleSample"
                              id
                              "call-gatk_applybqsr"
                              "cacheCopy"
                              "NA12878_PLUMBING.aln.mrkdp."])]
    {:end    now
     :id     id
     :inputs {:input_cram (str/join "/" ["gs://broad-gotc-test-storage"
                                         "germline_single_sample/wgs"
                                         "plumbing/truth/develop"
                                         "G96830.NA12878"
                                         "NA12878_PLUMBING.cram"])}
     :outputs
     {:GDCWholeGenomeSomaticSingleSample.bai (str prefix "bai")
      :GDCWholeGenomeSomaticSingleSample.bam (str prefix "bam")
      :GDCWholeGenomeSomaticSingleSample.contamination
      (str prefix "contam.txt")
      :GDCWholeGenomeSomaticSingleSample.insert_size_histogram_pdf
      (str prefix "insert_size_histogram.pdf")
      :GDCWholeGenomeSomaticSingleSample.insert_size_metrics
      (str prefix "insert_size_metrics")}
     :start        now
     :status       "Succeeded"
     :submission   now
     :workflowName sg/pipeline}))

(defn ^:private mock-cromwell-query-failed
  "Update `status` of all workflows to `Failed`."
  [_environment _params]
  (let [{:keys [items]} (the-sg-workload-request)]
    (map (fn [id] {:id id :status "Failed"})
         (take (count items) the-uuids))))

(defn ^:private mock-cromwell-query-succeeded
  "Update `status` of all workflows to `Succeeded`."
  [_environment _params]
  (let [{:keys [items]} (the-sg-workload-request)]
    (map (fn [id] {:id id :status "Succeeded"})
         (take (count items) the-uuids))))

(defn ^:private mock-cromwell-submit-workflows
  [_environment _wdl inputs _options _labels]
  (take (count inputs) the-uuids))

(defn mock-gcs-upload-content-fail
  "Fail when called because nothing should be uploaded."
  [_content _url]
  (is false))

(defn mock-gcs-upload-content
  "Mock uploading `content` to `url`."
  [content url]
  (letfn [(parse [url] (drop-last (str/split url #"/")))
          (tail? [end] (str/ends-with? url end))]
    (let [md  [:outputs :GDCWholeGenomeSomaticSingleSample.contamination]
          ok? (partial = (parse url))
          edn (util/parse-json content)]
      (is (cond (tail? "/clio-bam-record.json")
                (ok? (parse (:bai_path edn)))
                (tail? "/cromwell-metadata.json")
                (ok? (parse (get-in edn md)))
                :else false)))))

(def ^:private bam-suffixes
  "Map Clio BAM record fields to expected file suffixes."
  {:bai_path                 ".bai"
   :bam_path                 ".bam"
   :insert_size_metrics_path ".insert_size_metrics"})

(defn ^:private test-clio-updates
  []
  (let [{:keys [items] :as request} (the-sg-workload-request)]
    (-> request
        workloads/execute-workload!
        workloads/update-workload!
        (as-> workload
              (let [{:keys [finished pipeline]} workload]
                (is finished)
                (is (= sg/pipeline pipeline))
                (is (= (count items) (-> workload workloads/workflows count))))))))

(deftest test-clio-updates-bam-found
  (testing "Clio not updated if outputs already known."
    (with-redefs-fn
      {#'clio/add-bam              mock-clio-add-bam-found
       #'clio/query-bam            mock-clio-query-bam-found
       #'clio/query-cram           mock-clio-query-cram-found
       #'cromwell/metadata         mock-cromwell-metadata-succeeded
       #'cromwell/query            mock-cromwell-query-succeeded
       #'cromwell/submit-workflows mock-cromwell-submit-workflows
       #'gcs/upload-content        mock-gcs-upload-content-fail}
      test-clio-updates)))

(deftest test-clio-updates-bam-missing
  (testing "Clio updated after workflows finish."
    (with-redefs-fn
      {#'clio/add-bam              mock-clio-add-bam-missing
       #'clio/query-bam            mock-clio-query-bam-missing
       #'clio/query-cram           mock-clio-query-cram-found
       #'cromwell/metadata         mock-cromwell-metadata-succeeded
       #'cromwell/query            mock-cromwell-query-succeeded
       #'cromwell/submit-workflows mock-cromwell-submit-workflows
       #'gcs/upload-content        mock-gcs-upload-content}
      test-clio-updates)))

(deftest test-clio-updates-cromwell-failed
  (testing "Clio not updated after workflows fail."
    (with-redefs-fn
      {#'clio/add-bam              mock-clio-failed
       #'clio/query-bam            mock-clio-failed
       #'clio/query-cram           mock-clio-failed
       #'cromwell/metadata         mock-cromwell-metadata-failed
       #'cromwell/query            mock-cromwell-query-failed
       #'cromwell/submit-workflows mock-cromwell-submit-workflows
       #'gcs/upload-content        mock-gcs-upload-content-fail}
      test-clio-updates)))

(defn workflow-postcheck
  [output {:keys [uuid] :as _workflow}]
  (let [md (cromwell/metadata @workloads/cromwell-url uuid)
        bam (get-in md [:outputs :GDCWholeGenomeSomaticSingleSample.bam])
        bam_path (sg/final_workflow_outputs_dir_hack output bam)]
    (is (seq (clio/query-bam @workloads/clio-url {:bam_path bam_path})))))

(defmethod workloads/postcheck sg/pipeline postcheck-sg-workload
  [{:keys [output] :as workload}]
  (run! (partial workflow-postcheck output) (workloads/workflows workload)))

(defn ^:private mock-batch-update-workflow-statuses!
  [status tx {:keys [items] :as workload}]
  (letfn [(update! [{:keys [id]}]
            (jdbc/update! tx items
                          {:status status :updated (OffsetDateTime/now)}
                          ["id = ?" id]))]
    (run! update! (wfl.api.workloads/workflows tx workload))))

(deftest test-workload-state-transition
  (let [count           (atom 0)
        increment-count (fn [& _] (swap! count inc))]
    (with-redefs-fn
      {#'cromwell/submit-workflows             mock-cromwell-submit-workflows
       #'batch/batch-update-workflow-statuses! (partial mock-batch-update-workflow-statuses! "Succeeded")
       #'sg/register-workload-in-clio          increment-count}
      #(shared/run-workload-state-transition-test! (the-sg-workload-request)))
    (is (== 1 @count) "Clio was updated more than once")))

(deftest test-stop-workload-state-transition
  (shared/run-stop-workload-state-transition-test! (the-sg-workload-request)))

(deftest test-retry-workflows-supported
  (with-redefs-fn
    {#'cromwell/submit-workflows             mock-cromwell-submit-workflows
     #'batch/batch-update-workflow-statuses! (partial mock-batch-update-workflow-statuses! "Failed")}
    #(shared/run-workload-state-transition-test! (the-sg-workload-request))))

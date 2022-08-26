(ns wfl.integration.modules.sg-test
  (:require [clojure.test                   :refer [deftest is testing
                                                    use-fixtures]]
            [clojure.string                 :as str]
            [wfl.api.workloads]             ; for mocking
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
  "A request suitable when all external services are mocked."
  []
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
  "-Is-A-Clio-Record-Id")

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

(defn ^:private test-clio-updates
  "Assert that Clio is updated correctly."
  []
  (let [{:keys [items] :as request} (the-sg-workload-request)]
    (-> request
        workloads/execute-workload!
        workloads/update-workload!
        (as-> workload
            (let [{:keys [finished pipeline]} workload]
              (is finished)
              (is (= sg/pipeline pipeline))
              (is (= (count items)
                     (-> workload workloads/workflows count))))))))

(defn ^:private mock-add-bam-suggest-force=true
  "Throw rejecting the `_md` update to `_clio` and suggesting force=true."
  [_clio {:keys [bam_path version] :as _md}]
  (if (= 23 version)
    (let [adding  (str/join \space   ["Adding this document will overwrite"
                                      "the following existing metadata:"])
          field   "Field: Chain(Left(bam_path)),"
          oldval  "Old value: \"gs://bam/oldval.bam\","
          newval  (format "New value: \"%s\"." bam_path)
          force   "Use 'force=true' to overwrite the existing data."
          message (str/join \space   [field oldval newval force])
          body    (str/join \newline [adding message])]
      (throw (ex-info "clj-http: status 400" {:body          body
                                              :reason-phrase "Bad Request"
                                              :status        400})))
    (is (= 24 version) "-Is-A-Clio-Record-Id")))

(defn ^:private mock-add-bam-throw-something-else
  "Throw on the `_md` update to `_clio` without suggesting force=true."
  [_clio {:keys [bam_path version] :as _md}]
  (throw (ex-info "clj-http: status 500" {:body          "You goofed!"
                                          :reason-phrase "Blame tbl."
                                          :status        500})))

(deftest test-handle-add-bam-force=true
  (testing "Retry add-bam when Clio suggests force=true."
    (with-redefs [clio/add-bam              mock-add-bam-suggest-force=true
                  clio/query-bam            mock-clio-query-bam-missing
                  clio/query-cram           mock-clio-query-cram-found
                  cromwell/metadata         mock-cromwell-metadata-succeeded
                  cromwell/query            mock-cromwell-query-succeeded
                  cromwell/submit-workflows mock-cromwell-submit-workflows
                  gcs/upload-content        mock-gcs-upload-content]
      (test-clio-updates)))
  (testing "Do not retry when Clio rejects add-bam for another reason."
    (with-redefs [clio/add-bam              mock-add-bam-throw-something-else
                  clio/query-bam            mock-clio-query-bam-missing
                  clio/query-cram           mock-clio-query-cram-found
                  cromwell/metadata         mock-cromwell-metadata-succeeded
                  cromwell/query            mock-cromwell-query-succeeded
                  cromwell/submit-workflows mock-cromwell-submit-workflows
                  gcs/upload-content        mock-gcs-upload-content]
      (is (thrown-with-msg? Exception #"You goofed!" (test-clio-updates))))))

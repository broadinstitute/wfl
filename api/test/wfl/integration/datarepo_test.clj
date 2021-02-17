(ns wfl.integration.datarepo-test
  (:require [clojure.data.json :as json]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [ring.util.mime-type :as mime-type]
            [wfl.environment :as env]
            [wfl.service.datarepo :as datarepo]
            [wfl.service.google.storage :as gcs]
            [wfl.tools.fixtures :as fixtures]
            [wfl.util :as util])
  (:import [java.util UUID]))

;; UUIDs known to the Data Repo.
;;
(def dataset "f359303e-15d7-4cd8-a4c7-c50499c90252")
(def profile "390e7a85-d47f-4531-b612-165fc977d3bd")

(def ^:private assemble-refbased-outputs-dataset
  "test/resources/datasets/assemble-refbased-outputs.json")

(defn ^:private make-dataset-request [dataset-json-path]
  (-> (slurp dataset-json-path)
      json/read-str
    ;; give it a unique name to avoid collisions with other tests
      (update "name" #(str % (-> (UUID/randomUUID) (str/replace "-" ""))))
      (update "defaultProfileId" (constantly profile))))

(deftest test-create-dataset
  ;; To test that your dataset json file is valid, add its path to the list!
  (doseq [definition [assemble-refbased-outputs-dataset]]
    (testing (str "creating dataset " (util/basename definition))
      (fixtures/with-temporary-dataset (make-dataset-request definition)
        #(let [dataset (datarepo/dataset %)]
           (is (= % (:id dataset))))))))

(def assemble-refbased-outputs-type-environment
  {:num_read_groups                              {:typeName "Int"}
   :align_to_ref_merged_coverage_plot            {:typeName "File"}
   :align_to_ref_viral_core_version              {:typeName "String"}
   :align_to_self_merged_coverage_plot           {:typeName "File"}
   :assembly_length_unambiguous                  {:typeName "Int"}
   :assembly_mean_coverage                       {:typeName "Float"}
   :align_to_self_merged_bases_aligned           {:typeName "Float"}
   :align_to_ref_merged_bases_aligned            {:typeName "Float"}
   :align_to_ref_per_input_aligned_flagstat      {:typeName  "Array"
                                                  :arrayType {:typeName "File"}
                                                  :nonEmpty  false}
   :reference_genome_length                      {:typeName "Int"}
   :align_to_ref_per_input_reads_provided        {:typeName  "Array"
                                                  :arrayType {:typeName "Int"}
                                                  :nonEmpty  false}
   :align_to_ref_merged_coverage_tsv             {:typeName "File"}
   :assembly_length                              {:typeName "Int"}
   :replicate_discordant_indels                  {:typeName "Int"}
   :align_to_ref_merged_aligned_trimmed_only_bam {:typeName "File"}
   :align_to_ref_per_input_reads_aligned         {:typeName  "Array"
                                                  :arrayType {:typeName "Int"}
                                                  :nonEmpty  false}
   :replicate_discordant_vcf                     {:typeName "File"}
   :viral_assemble_version                       {:typeName "String"}
   :dist_to_ref_snps                             {:typeName "Int"}
   :align_to_self_merged_aligned_only_bam        {:typeName "File"}
   :num_libraries                                {:typeName "Int"}
   :align_to_self_merged_read_pairs_aligned      {:typeName "Int"}
   :align_to_ref_variants_vcf_gz                 {:typeName "File"}
   :align_to_ref_merged_read_pairs_aligned       {:typeName "Int"}
   :align_to_self_merged_coverage_tsv            {:typeName "File"}
   :replicate_concordant_sites                   {:typeName "Int"}
   :align_to_self_merged_reads_aligned           {:typeName "Int"}
   :dist_to_ref_indels                           {:typeName "Int"}
   :replicate_discordant_snps                    {:typeName "Int"}
   :ivar_version                                 {:typeName "String"}
   :assembly_fasta                               {:typeName "File"}
   :align_to_self_merged_mean_coverage           {:typeName "Float"}
   :align_to_ref_merged_reads_aligned            {:typeName "Int"}
   :align_to_ref_per_input_fastqc                {:typeName  "Array"
                                                  :arrayType {:typeName "File"}
                                                  :nonEmpty  false}})

(def assemble-refbased-outputs
  {:num_read_groups                              2
   :align_to_ref_merged_coverage_plot            "gs://broad-gotc-dev-wfl-ptc-test-inputs/covid-19/assemble_refbased/call-plot_ref_coverage/MA_MGH_02760.lERCC-00134_RandomPrimer-SSIV_NexteraXT_RNA_0352325199_cDNA_0369438842_LIB_13215203_G2_HTHC3DRXX_L1.cleaned.coverage_plot.pdf"
   :align_to_ref_viral_core_version              "v2.1.19"
   :align_to_self_merged_coverage_plot           "gs://broad-gotc-dev-wfl-ptc-test-inputs/covid-19/assemble_refbased/call-plot_self_coverage/MA_MGH_02760.lERCC-00134_RandomPrimer-SSIV_NexteraXT_RNA_0352325199_cDNA_0369438842_LIB_13215203_G2_HTHC3DRXX_L1.cleaned.coverage_plot.pdf"
   :assembly_length_unambiguous                  29903
   :assembly_mean_coverage                       1064.1900143798282
   :align_to_self_merged_bases_aligned           31834594
   :align_to_ref_merged_bases_aligned            31822474
   :align_to_ref_per_input_aligned_flagstat      ["gs://broad-gotc-dev-wfl-ptc-test-inputs/covid-19/assemble_refbased/call-align_to_ref/shard-0/MA_MGH_02760.lERCC-00134_RandomPrimer-SSIV_NexteraXT_RNA_0352325199_cDNA_0369438842_LIB_13215203_G2_HTHC3DRXX_L1.cleaned.all.bam.flagstat.txt"
                                                  "gs://broad-gotc-dev-wfl-ptc-test-inputs/covid-19/assemble_refbased/call-align_to_ref/shard-1/MA_MGH_02760.lERCC-00134_RandomPrimer-SSIV_NexteraXT_RNA_0352325199_cDNA_0369438842_LIB_13215203_G2_HTHC3DRXX_L2.cleaned.all.bam.flagstat.txt"]
   :reference_genome_length                      29903
   :align_to_ref_per_input_reads_provided        [539844 555716]
   :align_to_ref_merged_coverage_tsv             "gs://broad-gotc-dev-wfl-ptc-test-inputs/covid-19/assemble_refbased/call-plot_ref_coverage/MA_MGH_02760.lERCC-00134_RandomPrimer-SSIV_NexteraXT_RNA_0352325199_cDNA_0369438842_LIB_13215203_G2_HTHC3DRXX_L1.cleaned.coverage_plot.txt"
   :assembly_length                              29903
   :replicate_discordant_indels                  0
   :align_to_ref_merged_aligned_trimmed_only_bam "gs://broad-gotc-dev-wfl-ptc-test-inputs/covid-19/assemble_refbased/call-merge_align_to_ref/MA_MGH_02760.lERCC-00134_RandomPrimer-SSIV_NexteraXT_RNA_0352325199_cDNA_0369438842_LIB_13215203_G2_HTHC3DRXX_L1.cleaned.align_to_ref.trimmed.bam"
   :align_to_ref_per_input_reads_aligned         [157936 157138]
   :replicate_discordant_vcf                     "gs://broad-gotc-dev-wfl-ptc-test-inputs/covid-19/assemble_refbased/call-run_discordance/MA_MGH_02760.lERCC-00134_RandomPrimer-SSIV_NexteraXT_RNA_0352325199_cDNA_0369438842_LIB_13215203_G2_HTHC3DRXX_L1.cleaned.discordant.vcf"
   :viral_assemble_version                       "v2.1.16"
   :dist_to_ref_snps                             19
   :align_to_self_merged_aligned_only_bam        "gs://broad-gotc-dev-wfl-ptc-test-inputs/covid-19/assemble_refbased/call-merge_align_to_self/MA_MGH_02760.lERCC-00134_RandomPrimer-SSIV_NexteraXT_RNA_0352325199_cDNA_0369438842_LIB_13215203_G2_HTHC3DRXX_L1.cleaned.merge_align_to_self.bam"
   :num_libraries                                1
   :align_to_self_merged_read_pairs_aligned      315194
   :align_to_ref_variants_vcf_gz                 "gs://broad-gotc-dev-wfl-ptc-test-inputs/covid-19/assemble_refbased/call-call_consensus/MA_MGH_02760.lERCC-00134_RandomPrimer-SSIV_NexteraXT_RNA_0352325199_cDNA_0369438842_LIB_13215203_G2_HTHC3DRXX_L1.cleaned.sites.vcf.gz"
   :align_to_ref_merged_read_pairs_aligned       315074
   :align_to_self_merged_coverage_tsv            "gs://broad-gotc-dev-wfl-ptc-test-inputs/covid-19/assemble_refbased/call-plot_self_coverage/MA_MGH_02760.lERCC-00134_RandomPrimer-SSIV_NexteraXT_RNA_0352325199_cDNA_0369438842_LIB_13215203_G2_HTHC3DRXX_L1.cleaned.coverage_plot.txt"
   :replicate_concordant_sites                   29857
   :align_to_self_merged_reads_aligned           315194
   :dist_to_ref_indels                           0
   :replicate_discordant_snps                    0
   :ivar_version                                 "iVar version 1.3"
   :assembly_fasta                               "gs://broad-gotc-dev-wfl-ptc-test-inputs/covid-19/assemble_refbased/call-call_consensus/MA_MGH_02760.lERCC-00134_RandomPrimer-SSIV_NexteraXT_RNA_0352325199_cDNA_0369438842_LIB_13215203_G2_HTHC3DRXX_L1.cleaned.fasta"
   :align_to_self_merged_mean_coverage           1064.595324883791
   :align_to_ref_merged_reads_aligned            315074
   :align_to_ref_per_input_fastqc                ["gs://broad-gotc-dev-wfl-ptc-test-inputs/covid-19/assemble_refbased/call-align_to_ref/shard-0/MA_MGH_02760.lERCC-00134_RandomPrimer-SSIV_NexteraXT_RNA_0352325199_cDNA_0369438842_LIB_13215203_G2_HTHC3DRXX_L1.cleaned.mapped_fastqc.html"
                                                  "gs://broad-gotc-dev-wfl-ptc-test-inputs/covid-19/assemble_refbased/call-align_to_ref/shard-1/MA_MGH_02760.lERCC-00134_RandomPrimer-SSIV_NexteraXT_RNA_0352325199_cDNA_0369438842_LIB_13215203_G2_HTHC3DRXX_L2.cleaned.mapped_fastqc.html"]})

(def ^:private genomics-mime-types
  {"bam"   "application/octet-stream"
   "cram"  "application/octet-stream"
   "fasta" "application/octet-stream"
   "vcf"   "text/plain"})

(defn ^:private run-thunk! [x] (x))

(defn ^:private ingest!
  "Ingest `value` into the dataset specified by `dataset-id` depending on
   the `value`'s WDL `type` and return a thunk that performs any delayed work
   and returns an ingest-able value."
  [workload-id dataset-id profile-id type value]
  ;; Ingesting objects into TDR is asynchronous and must be done in two steps:
  ;; - Issue an "ingest" request for that object to TDR
  ;; - Poll the result of the request for the subsequent resource identifier
  ;;
  ;; Assuming TDR can fulfil ingest requests in parallel, we can (in theory)
  ;; increase throughput by issuing all ingest requests up front and then
  ;; poll for the resource identifiers later.
  ;;
  ;; To do this, this function returns a thunk that when run, performs
  ;; any delayed work needed to ingest an object of that data type (such as
  ;; polling for a file resource identifier) and returns a value that can
  ;; be ingested into a dataset table.
  (let [sequence    (fn [xs] #(mapv run-thunk! xs))
        return      (fn [x]   (constantly x))
        bind        (fn [f g] (comp g f))
        ingest-file (partial datarepo/ingest-file dataset-id profile-id)]
    ((fn go [type value]
       (case (:typeName type)
         "File"
         (let [[bkt obj] (gcs/parse-gs-url value)
               target    (str/join "/" ["" workload-id obj])
               mime      (mime-type/ext-mime-type value genomics-mime-types)]
           (-> (env/getenv "WFL_DATA_REPO_SA")
               (gcs/add-object-reader bkt obj))
           (-> (ingest-file value target {:mime_type mime})
               return
               (bind (comp :fileId datarepo/poll-job))))
         "Optional"
         (if value
           (go (:optionalType type) value)
           (return nil))
         "Array"
         (letfn [(ingest-elem! [x] (go (:arrayType type) x))]
           ;; eagerly issue ingest requests for each element in the array
           (sequence (mapv ingest-elem! value)))
         ("Boolean" "Float" "Int" "Number" "String")
         (return value)))
     type value)))

(deftest test-ingest-workflow-outputs
  (let [dataset-json     assemble-refbased-outputs-dataset
        pipeline-outputs assemble-refbased-outputs
        type             assemble-refbased-outputs-type-environment
        rename-gather    identity ;; collect and map outputs onto dataset names
        table-name       "assemble_refbased_outputs"
        workflow-id      (UUID/randomUUID)]
    (fixtures/with-fixtures
      [(fixtures/with-temporary-cloud-storage-folder fixtures/gcs-test-bucket)
       (fixtures/with-temporary-dataset (make-dataset-request dataset-json))]
      (fn [[url dataset-id]]
        (let [table-url (str url "table.json")
              ingest!   (partial ingest! workflow-id dataset-id profile)]
          (-> (->> pipeline-outputs
                   (map (fn [[name value]] {name (ingest! (type name) value)}))
                   (into {})
                   (util/map-vals run-thunk!)
                   rename-gather)
              (json/write-str :escape-slash false)
              (gcs/upload-content table-url))
          (gcs/add-object-reader (env/getenv "WFL_DATA_REPO_SA") table-url)
          (let [{:keys [bad_row_count row_count]}
                (datarepo/poll-job
                 (datarepo/ingest-table dataset-id table-url table-name))]
            (is (= 1 row_count))
            (is (= 0 bad_row_count))))))))

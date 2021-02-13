(ns wfl.integration.datarepo-test
  (:require [clojure.data.json :as json]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [ring.util.mime-type :as mime-type]
            [wfl.environments :as env]
            [wfl.service.datarepo :as datarepo]
            [wfl.service.google.storage :as gcs]
            [wfl.tools.fixtures :as fixtures]
            [wfl.util :as util])
  (:import [java.util UUID]))

;; UUIDs known to the Data Repo.
;;
(def dataset "f359303e-15d7-4cd8-a4c7-c50499c90252")
(def profile "390e7a85-d47f-4531-b612-165fc977d3bd")

(deftest delivery
  (fixtures/with-temporary-cloud-storage-folder fixtures/gcs-test-bucket
    (fn [url]
      (testing "delivery succeeds"
        (let [[bucket object] (gcs/parse-gs-url url)
              file-id     (UUID/randomUUID)
              vcf         (str file-id ".vcf")
              table       (str file-id ".tabular.json")
              vcf-url     (gcs/gs-url bucket (str object vcf))
              ingest-file (partial datarepo/ingest-file dataset profile)
              drsa        (get-in env/stuff [:debug :data-repo :service-account])]
          (letfn [(stage [file content]
                    (spit file content)
                    (gcs/upload-file file bucket (str object file))
                    (gcs/add-object-reader drsa bucket (str object file)))
                  (ingest [path vdest]
                    (let [job (ingest-file path vdest)]
                      (:fileId (datarepo/poll-job job))))
                  (cleanup []
                    (io/delete-file vcf)
                    (io/delete-file (str vcf ".tbi"))
                    (io/delete-file table))]
            (stage vcf "bogus vcf content")
            (stage (str vcf ".tbi") "bogus index content")
            (stage table (json/write-str
                          {:id        object
                           :vcf       (ingest vcf-url vcf)
                           :vcf_index (ingest vcf-url (str vcf ".tbi"))}
                          :escape-slash false))
            (let [table-url (gcs/gs-url bucket (str object table))
                  job       (datarepo/ingest-dataset dataset table-url "sample")
                  {:keys [bad_row_count row_count]} (datarepo/poll-job job)]
              (is (= 1 row_count))
              (is (= 0 bad_row_count)))
            (cleanup)))))))

(def ^:private assemble-refbased-outputs-dataset
  "test/resources/datasets/assemble-refbased-outputs.json")

(def ^:private dataset-types
  "test/resources/datasets/dataset-types.json")

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

(defn ^:private evaluate! [x] (x))
(defn ^:private bind [f g] (comp g f))

(defn ^:private make-ingest! [workload-id {:keys [id defaultProfileId]}]
  (letfn [(sequence [xs] #(mapv evaluate! xs))
          (return [x] (constantly x))]
    (fn ingest! [type value]
      (case (:typeName type)
        "File"
        (let [[bkt obj] (gcs/parse-gs-url value)
              target (str/join "/" ["" workload-id obj])
              mime   (mime-type/ext-mime-type value genomics-mime-types)]
          (-> (get-in env/stuff [:debug :data-repo :service-account])
              (gcs/add-object-reader bkt obj))
          (-> (datarepo/ingest-file id defaultProfileId value target {:mime_type mime})
              return
              (bind (comp :fileId datarepo/poll-job))))
        "Optional"
        (if value
          (ingest! (:optionalType type) value)
          (return nil))
        "Array"
        (letfn [(ingest-elem! [x] (ingest! (:arrayType type) x))]
          (sequence (mapv ingest-elem! value)))
        ("Boolean" "Float" "Int" "Number" "String")
        (return value)))))

(deftest test-ingest-workflow-outputs
  (let [dataset-json     assemble-refbased-outputs-dataset
        pipeline-outputs assemble-refbased-outputs
        type             assemble-refbased-outputs-type-environment
        dsname           identity
        table-name       "assemble_refbased_outputs"
        workflow-id      (UUID/randomUUID)]
    (fixtures/with-temporary-cloud-storage-folder fixtures/gcs-test-bucket
      (fn [url]
        (fixtures/with-temporary-folder
          (fn [temp]
            (fixtures/with-temporary-dataset (make-dataset-request dataset-json)
              (bind
               datarepo/dataset
               (fn [dataset]
                 (let [ingest!    (make-ingest! workflow-id dataset)
                       table-file (str/join "/" [temp "table.json"])
                       table-url  (str url "table.json")]
                   (-> (util/map-vals
                        evaluate!
                        (reduce-kv
                         #(merge %1 {(dsname %2) (ingest! (type %2) %3)})
                         {}
                         pipeline-outputs))
                       (json/write-str :escape-slash false)
                       (->> (spit table-file)))
                   (gcs/upload-file table-file table-url)
                   (let [sa (get-in env/stuff [:debug :data-repo :service-account])]
                     (gcs/add-object-reader sa table-url))
                   (let [{:keys [bad_row_count row_count]}
                         (-> (:id dataset)
                             (datarepo/ingest-dataset table-url table-name)
                             datarepo/poll-job)]
                     (is (= 1 row_count))
                     (is (= 0 bad_row_count)))))))))))))

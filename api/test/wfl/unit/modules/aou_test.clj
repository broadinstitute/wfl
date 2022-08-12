(ns wfl.unit.modules.aou-test
  (:require [clojure.test   :refer [deftest is testing]]
            [clojure.set    :as set]
            [wfl.module.aou :as aou]))

(def ^:private cromwell-url
  "https://cromwell-gotc-auth.gotc-dev.broadinstitute.org")

(deftest test-cromwell->inputs+options
  (testing "Map cromwell URL to inputs+options correctly"
    (is (= (:environment (aou/cromwell->inputs+options cromwell-url)) "dev"))))

(def input-keys
  "Per-sample input keys for AoU workflows."
  [:analysis_version_number
   :bead_pool_manifest_file
   :call_rate_threshold
   :chip_well_barcode
   :cluster_file
   :environment
   :extended_chip_manifest_file
   :gender_cluster_file
   :green_idat_cloud_path
   :minor_allele_frequency_file
   :params_file
   :red_idat_cloud_path
   :reported_gender
   :sample_alias
   :sample_lsid
   :zcall_thresholds_file])

(def sample-inputs
  "Bogus per-sample input for AoU workflows."
  (-> input-keys
      (zipmap (map name input-keys))
      (assoc :analysis_version_number 23)))

(deftest test-make-cromwell-labels
  (testing "make-labels can return correct workflow labels"
    (let [labels {:workload "bogus-workload"}]
      (is (= (aou/make-labels sample-inputs labels)
             (-> sample-inputs
                 (select-keys [:analysis_version_number 23
                               :chip_well_barcode "chip_well_barcode"])
                 (merge {:wfl "AllOfUsArrays"} labels)))
          "label map is not made as expected"))))

(deftest test-aou-inputs-preparation
  (let [extra-inputs   (merge sample-inputs {:extra "extra"})
        inputs-missing (dissoc sample-inputs
                               :analysis_version_number)
        no-controls    #{:Arrays.analysis_version_number
                         :Arrays.bead_pool_manifest_file
                         :Arrays.call_rate_threshold
                         :Arrays.chip_well_barcode
                         :Arrays.cluster_file
                         :Arrays.contamination_controls_vcf
                         :Arrays.dbSNP_vcf
                         :Arrays.dbSNP_vcf_index
                         :Arrays.disk_size
                         :Arrays.environment
                         :Arrays.extended_chip_manifest_file
                         :Arrays.fingerprint_genotypes_vcf_file
                         :Arrays.fingerprint_genotypes_vcf_index_file
                         :Arrays.gender_cluster_file
                         :Arrays.green_idat_cloud_path
                         :Arrays.haplotype_database_file
                         :Arrays.minor_allele_frequency_file
                         :Arrays.params_file
                         :Arrays.preemptible_tries
                         :Arrays.red_idat_cloud_path
                         :Arrays.ref_dict
                         :Arrays.ref_fasta
                         :Arrays.ref_fasta_index
                         :Arrays.reported_gender
                         :Arrays.sample_alias
                         :Arrays.sample_lsid
                         :Arrays.subsampled_metrics_interval_list
                         :Arrays.variant_rsids_file
                         :Arrays.vault_token_path
                         :Arrays.zcall_thresholds_file}
        all-keys       (set/union
                        no-controls
                        #{:Arrays.control_sample_intervals_file
                          :Arrays.control_sample_name
                          :Arrays.control_sample_vcf_file
                          :Arrays.control_sample_vcf_index_file})]
    (testing "aou filters out non-necessary keys for per-sample-inputs"
      (is (= sample-inputs (aou/get-per-sample-inputs extra-inputs))))
    (testing "aou throws for missing keys for per-sample-inputs"
      (is (thrown? Exception (aou/get-per-sample-inputs inputs-missing))))
    (testing "aou prepares all necessary keys"
      (is (= no-controls (-> cromwell-url
                             (aou/make-inputs sample-inputs)
                             keys set))))
    (testing "aou supplies merges environment from inputs with default"
      (let [no-environment (dissoc sample-inputs :environment)]
        (is (= "dev"         (-> cromwell-url
                                 (aou/make-inputs no-environment)
                                 :Arrays.environment)))
        (is (= "environment" (-> cromwell-url
                                 (aou/make-inputs sample-inputs)
                                 :Arrays.environment)))
        (is (= no-controls   (-> cromwell-url
                                 (aou/make-inputs no-environment)
                                 keys set)))))
    (testing "aou prepares all necessary keys plus optional keys"
      (is (= all-keys (->> {:control_sample_vcf_index_file "foo"
                            :control_sample_intervals_file "foo"
                            :control_sample_vcf_file       "foo"
                            :control_sample_name           "foo"}
                           (merge sample-inputs)
                           (aou/make-inputs cromwell-url)
                           keys set))))))

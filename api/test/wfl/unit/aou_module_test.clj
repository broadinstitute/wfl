(ns wfl.unit.aou-module-test
  (:require [clojure.test :refer :all]
            [clojure.set  :as set]
            [wfl.module.aou :as aou]))

(deftest test-map-aou-environment
  (testing "maps environments for aou correctly"
    (is (= (aou/map-aou-environment :aou-dev) "dev"))
    (is (= (aou/map-aou-environment :aou-prod) "prod"))))

(deftest test-make-cromwell-labels
  (let [sample          {:analysis_version_number     1
                         :bead_pool_manifest_file     "foo"
                         :chip_well_barcode           "chip"
                         :cluster_file                "foo"
                         :extended_chip_manifest_file "foo"
                         :gender_cluster_file         "foo"
                         :green_idat_cloud_path       "foo"
                         :params_file                 "foo"
                         :red_idat_cloud_path         "foo"
                         :reported_gender             "foo"
                         :sample_alias                "foo"
                         :sample_lsid                 "foo"
                         :zcall_thresholds_file       "foo"}
        workload->label {:workload "bogus-workload"}
        expected        (merge {:wfl "AllOfUsArrays"
                                :analysis_version_number 1
                                :chip_well_barcode "chip"} 
                               workload->label)]
    (testing "make-labels can return correct workflow labels"
      (is (= (aou/make-labels sample workload->label) expected) "label map is not made as expected"))))

(deftest test-aou-inputs-preparation
  (let [expected-per-sample-inputs         {:analysis_version_number     "foo"
                                            :bead_pool_manifest_file     "foo"
                                            :call_rate_threshold         "foo"
                                            :chip_well_barcode           "foo"
                                            :cluster_file                "foo"
                                            :extended_chip_manifest_file "foo"
                                            :gender_cluster_file         "foo"
                                            :green_idat_cloud_path       "foo"
                                            :params_file                 "foo"
                                            :red_idat_cloud_path         "foo"
                                            :reported_gender             "foo"
                                            :sample_alias                "foo"
                                            :sample_lsid                 "foo"
                                            :zcall_thresholds_file       "foo"}
        redundant-per-sample-inputs-inputs (merge expected-per-sample-inputs {:extra "bar"})
        missing-per-sample-inputs-inputs   (dissoc expected-per-sample-inputs :analysis_version_number)
        all-expected-keys-no-control       #{:Arrays.preemptible_tries
                                             :Arrays.environment
                                             :Arrays.ref_dict
                                             :Arrays.params_file
                                             :Arrays.subsampled_metrics_interval_list
                                             :Arrays.chip_well_barcode
                                             :Arrays.sample_alias
                                             :Arrays.variant_rsids_file
                                             :Arrays.ref_fasta_index
                                             :Arrays.dbSNP_vcf
                                             :Arrays.disk_size
                                             :Arrays.contamination_controls_vcf
                                             :Arrays.green_idat_cloud_path
                                             :Arrays.fingerprint_genotypes_vcf_index_file
                                             :Arrays.vault_token_path
                                             :Arrays.reported_gender
                                             :Arrays.dbSNP_vcf_index
                                             :Arrays.extended_chip_manifest_file
                                             :Arrays.zcall_thresholds_file
                                             :Arrays.sample_lsid
                                             :Arrays.red_idat_cloud_path
                                             :Arrays.gender_cluster_file
                                             :Arrays.ref_fasta
                                             :Arrays.bead_pool_manifest_file
                                             :Arrays.analysis_version_number
                                             :Arrays.fingerprint_genotypes_vcf_file
                                             :Arrays.cluster_file
                                             :Arrays.call_rate_threshold
                                             :Arrays.haplotype_database_file}
        all-expected-keys                   (set/union all-expected-keys-no-control
                                              #{:Arrays.control_sample_vcf_index_file
                                                :Arrays.control_sample_intervals_file
                                                :Arrays.control_sample_vcf_file
                                                :Arrays.control_sample_name})]
    (testing "aou filters out non-necessary keys for per-sample-inputs"
      (is (= expected-per-sample-inputs (aou/get-per-sample-inputs redundant-per-sample-inputs-inputs))))
    (testing "aou throws for missing keys for per-sample-inputs"
      (is (thrown? Exception (aou/get-per-sample-inputs missing-per-sample-inputs-inputs))))
    (testing "aou prepares all necessary keys"
      (is (= all-expected-keys-no-control (set (keys (aou/make-inputs :aou-dev expected-per-sample-inputs))))))
    (testing "aou prepares all necessary keys plus optional keys"
      (is (= all-expected-keys (set (keys (aou/make-inputs :aou-dev (merge expected-per-sample-inputs
                                                                      {:control_sample_vcf_index_file "foo"
                                                                       :control_sample_intervals_file "foo"
                                                                       :control_sample_vcf_file       "foo"
                                                                       :control_sample_name           "foo"})))))))))

(ns wfl.unit.modules.aou-test
  (:require [clojure.test   :refer [deftest is testing]]
            [wfl.module.aou :as aou]))

(def ^:private per-sample
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

(def ^:private other-keys
  "Input keys that are not per-sample."
  [:contamination_controls_vcf
   :dbSNP_vcf
   :dbSNP_vcf_index
   :disk_size
   :fingerprint_genotypes_vcf_file
   :fingerprint_genotypes_vcf_index_file
   :haplotype_database_file
   :preemptible_tries
   :ref_dict
   :ref_fasta
   :ref_fasta_index
   :subsampled_metrics_interval_list
   :variant_rsids_file
   :vault_token_path])

(def ^:private control-keys
  "Keys that mark control samples."
  [:control_sample_vcf_index_file
   :control_sample_intervals_file
   :control_sample_vcf_file
   :control_sample_name])

(def ^:private per-sample-inputs
  "Bogus per-sample input for AoU workflows."
  (-> per-sample
      (zipmap (map name per-sample))
      (assoc :analysis_version_number 23)))

(deftest test-make-cromwell-labels
  (testing "make-labels can return correct workflow labels"
    (let [labels {:workload "bogus-workload"}]
      (is (= (aou/make-labels per-sample-inputs labels)
             (-> per-sample-inputs
                 (select-keys [:analysis_version_number :chip_well_barcode])
                 (merge {:wfl "AllOfUsArrays"} labels)))
          "label map is not made as expected"))))

(defn ^:private arraysify
  "The keywords in KWS prefixed with `Arrays.`."
  [kws]
  (map (fn [k] (keyword (str "Arrays." (name k)))) kws))

(deftest test-aou-inputs-preparation
  (let [cromwell-url   "https://cromwell-gotc-auth.gotc-dev.broadinstitute.org"
        extra-inputs   (merge per-sample-inputs {:extra "extra"})
        inputs-missing (dissoc per-sample-inputs :analysis_version_number)
        no-controls    (-> other-keys (concat per-sample) arraysify set)
        all-keys       (-> control-keys arraysify (concat no-controls) set)]
    (testing "aou filters out non-necessary keys for per-sample-inputs"
      (is (= per-sample-inputs (aou/get-per-sample-inputs extra-inputs))))
    (testing "aou throws for missing keys for per-sample-inputs"
      (is (thrown? Exception (aou/get-per-sample-inputs inputs-missing))))
    (testing "aou prepares all necessary keys"
      (is (= no-controls (-> cromwell-url
                             (aou/make-inputs per-sample-inputs)
                             keys set))))
    (testing "aou suppolies merges environment from inputs with default"
      (let [no-environment (dissoc per-sample-inputs :environment)]
        (is (= "dev"         (->> no-environment
                                  (aou/make-inputs cromwell-url)
                                  :Arrays.environment)))
        (is (= "environment" (->> per-sample-inputs
                                  (aou/make-inputs cromwell-url)
                                  :Arrays.environment)))
        (is (= no-controls   (->> no-environment
                                  (aou/make-inputs cromwell-url)
                                  keys set)))))
    (testing "aou prepares all necessary keys plus optional keys"
      (is (= all-keys (->> control-keys
                           (map name)
                           (zipmap control-keys)
                           (merge per-sample-inputs)
                           (aou/make-inputs cromwell-url)
                           keys set))))))

(deftest test-no-workflow-on-stopped-workload
  (testing "Cannot add a workflow to a stopped workload"
    (is false)))

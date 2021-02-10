(ns wfl.unit.modules.wgs-test
  (:require [clojure.test :refer :all]
            [wfl.module.wgs :as wgs]))

(def ^:private output-url "gs://fake-output-bucket/")

(deftest test-make-inputs-from-cram
  (let [sample "gs://fake-input-bucket/folder/sample.cram"
        inputs (wgs/make-inputs-to-save output-url {:input_cram sample})]
    (is (= sample (:input_cram inputs)))
    (is (= "sample" (:sample_name inputs)))
    (is (= "sample" (:base_file_name inputs)))
    (is (= "sample" (:final_gvcf_base_name inputs)))
    (is (= ".unmapped.bam" (:unmapped_bam_suffix inputs)))
    (is (= (str output-url "folder") (:destination_cloud_path inputs)))))

(deftest test-make-inputs-from-bam
  (let [sample "gs://fake-input-bucket/folder/sample.bam"
        inputs (wgs/make-inputs-to-save output-url {:input_bam sample})]
    (is (= sample (:input_bam inputs)))
    (is (= "sample" (:sample_name inputs)))
    (is (= "sample" (:base_file_name inputs)))
    (is (= "sample" (:final_gvcf_base_name inputs)))
    (is (= ".unmapped.bam" (:unmapped_bam_suffix inputs)))
    (is (= (str output-url "folder") (:destination_cloud_path inputs)))))

(deftest test-specifying-destination_cloud_path
  (let [destination "gs://some-bucket/in-the-middle/of-nowhere.out"
        inputs      (wgs/make-inputs-to-save output-url
                                             {:input_bam              "gs://fake-input-bucket/sample.bam"
                                              :destination_cloud_path destination})]
    (is (= destination (:destination_cloud_path inputs)))))

(deftest test-specifying-sample_name
  (let [name   "geoff"
        inputs (wgs/make-inputs-to-save output-url
                                        {:input_bam   "gs://fake-input-bucket/sample.bam"
                                         :sample_name name})]
    (is (= name (:sample_name inputs)))))

(deftest test-specifying-arbitrary-workflow-inputs
  (is (:arbitrary
       (wgs/make-inputs-to-save output-url
                                {:input_bam "gs://fake-input-bucket/sample.bam"
                                 :arbitrary "hai"}))))

(deftest test-invalid-input-gs-url-throws
  (is (thrown? Exception
               (wgs/make-inputs-to-save
                output-url
                {:input_cram "https://domain/sample.cram"})))
  (is (thrown? Exception
               (wgs/make-inputs-to-save
                output-url
                {:input_cram "https://domain/sample.cram"}))))

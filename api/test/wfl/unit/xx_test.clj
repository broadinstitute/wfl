(ns wfl.unit.xx-test
  (:require [clojure.test :refer :all]
            [wfl.module.xx :as xx]))

(deftest test-make-inputs
  (testing "make-inputs from cram"
    (let [output "gs://output"
          items  {:input_cram "gs://input/sample.cram"}]
      (is (xx/make-combined-inputs-to-save output {} items))))
  (testing "make-inputs from bam"
    (let [output "gs://output"
          items  {:input_bam "gs://input/sample.bam"}]
      (is (xx/make-combined-inputs-to-save output {} items)))))

(deftest test-common-inputs
  (testing "add common overrides to the workflows"
    (let [common {:bait_set_name "frank"}
          output "gs://output"
          items  {:input_cram "gs://input/sample.cram"}]
      (is (= "frank" (:bait_set_name (xx/make-combined-inputs-to-save output common items)))))))

(deftest test-override-precedence
  (testing "add common overrides to the workflows"
    (let [common {:bait_set_name "frank"}
          output "gs://output"
          items  {:input_cram    "gs://input/sample.cram"
                  :bait_set_name "geoff"}]
      (is (= "geoff" (:bait_set_name (xx/make-combined-inputs-to-save output common items)))))))

(deftest sample-name-behaviour
  (testing "specifying sample name"
    (let [output "gs://output"
          items  {:input_cram  "gs://input/sample.cram"
                  :sample_name "dave"}]
      (is (= "dave" (:sample_name (xx/make-combined-inputs-to-save output {} items))))))
  (testing "computing the sample name"
    (let [output "gs://output"
          items  {:input_cram "gs://input/sample.foo.bar.baz.cram"}]
      (is (= "sample" (:sample_name (xx/make-combined-inputs-to-save output {} items)))))))

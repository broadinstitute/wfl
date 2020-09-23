(ns wfl.unit.xx-test
  (:require [clojure.test :refer :all]
            [wfl.module.xx :as xx]))

(deftest test-make-inputs
  (testing "make-inputs from cram"
    (let [common {}
          output "gs://output"
          items  {:input_cram "gs://input/sample.cram"}]
      (is (xx/make-inputs output common items))))
  (testing "make-inputs from bam"
    (let [common {}
          output "gs://output"
          items  {:input_bam "gs://input/sample.bam"}]
      (is (xx/make-inputs output common items)))))

(deftest test-common-inputs
  (testing "add common overrides to the workflows"
    (let [common {:bait_set_name "frank"}
          output "gs://output"
          items  {:input_cram "gs://input/sample.cram"}]
      (is (= "frank" (:bait_set_name (xx/make-inputs output common items)))))))

(deftest test-override-precedence
  (testing "add common overrides to the workflows"
    (let [common {:bait_set_name "frank"}
          output "gs://output"
          items  {:input_cram    "gs://input/sample.cram"
                  :bait_set_name "geoff"}]
      (is (= "geoff" (:bait_set_name (xx/make-inputs output common items)))))))

(ns wfl.unit.references-test
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest testing is]]
            [wfl.references :as references]))

(deftest prefix-handling
  (testing "reference_fasta function"
    (testing "supplies a bucket prefix if no arg"
      (run! #(is (str/starts-with? % "gs://"))
            (vals (references/reference_fasta))))
    (testing "uses a given prefix"
      (let [prefix "foo"]
        (run! #(is (str/starts-with? % prefix))
              (vals (references/reference_fasta prefix))))))
  (testing "hg38-genome-references function"
    (testing "supplies a reference_fasta default if nil"
      (run! #(is (str/starts-with? % "gs://"))
            (vals (:reference_fasta (references/hg38-genome-references nil)))))
    (testing "uses a given prefix for reference_fasta"
      (let [prefix "bar"]
        (run! #(is (str/starts-with? % prefix))
              (vals (:reference_fasta (references/hg38-genome-references prefix))))))))

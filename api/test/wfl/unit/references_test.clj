(ns wfl.unit.references-test
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest testing is]]
            [wfl.references :as references]))

(deftest prefix-handling
  (testing "reference_fasta function"
    (testing "treats nil/empty as no arg"
      (is (= (references/reference_fasta)
             (references/reference_fasta nil)
             (references/reference_fasta ""))))
    (testing "supplies a bucket prefix if no arg"
      (run! #(is (str/starts-with? % "gs://")) (vals (references/reference_fasta))))
    (testing "uses a given prefix"
      (let [prefix "foo"]
        (run! #(is (str/starts-with? % prefix)) (vals (references/reference_fasta prefix)))))))

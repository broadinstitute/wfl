(ns wfl.unit.mime-type-test
  (:require [clojure.test :refer [deftest is testing]]
            [wfl.mime-type :as mime-type]))

(deftest test-mime-types
  (let [cases [[".pdf"   "application/pdf"]
               [".txt"   "text/plain"]
               [".bam"   "application/octet-stream"]
               [".cram"  "application/octet-stream"]
               [".vcf"   "text/plain"]
               [".gz"    "application/gzip"]
               [".fasta" "application/octet-stream"]
               [".html"  "text/html"]]]
    (doseq [[filename expected] cases]
      (testing filename
        (is (= expected (mime-type/ext-mime-type filename)))))))

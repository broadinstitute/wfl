(ns wfl.unit.mime-type-test
  "Test wfl.mime-type."
  (:require [clojure.test  :refer [deftest is]]
            [wfl.mime-type :as mime-type]))

(deftest test-mime-types
  (let [cases [[".bam"   "application/octet-stream"]
               [".cram"  "application/octet-stream"]
               [".fasta" "application/octet-stream"]
               [".gz"    "application/gzip"]
               [".html"  "text/html"]
               [".pdf"   "application/pdf"]
               [".ready" "text/plain"]
               [".tsv"   "text/tab-separated-values"]
               [".txt"   "text/plain"]
               [".vcf"   "text/plain"]]]
    (doseq [[filename expected] cases]
      (is (= expected (mime-type/ext-mime-type filename))
          (str filename " does not have the expected mime-type")))))

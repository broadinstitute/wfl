(ns wfl.unit.mime-type-test
  (:require [clojure.test        :refer [deftest is]]
            [wfl.mime-type       :as mime-type]
            [wfl.tools.resources :as resources]
            [wfl.tools.workflows :as workflows]))

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

(deftest test-workflow-mime-types
  (let [exclude? #{"gs://fc-f9083173-1e35-4c1b-9d80-d837d6d12283/475d0a1d-20c0-42a1-968a-7540b79fcf0c/sarscov2_illumina_full/2768b29e-c808-4bd6-a46b-6c94fd2a67aa/call-demux_deplete/demux_deplete/7045e3bd-cf81-401d-b7da-4eee334cc937/call-sra_meta_prep/cacheCopy/write_lines_0128f217b922021a3b05cdc678f982d2.tmp"}
        cases [[(resources/read-resource "assemble_refbased/outputs.edn")
                (-> "assemble_refbased.edn" resources/read-resource :outputs)]
               [(resources/read-resource "sarscov2_illumina_full/outputs.edn")
                (-> "sarscov2_illumina_full.edn" resources/read-resource :outputs)]]]
    (doseq [[values description] cases]
      (let [type (workflows/make-object-type description)]
        (doseq [filename (workflows/get-files type values)]
          (let [ext (mime-type/ext-mime-type-no-default filename)]
            (is (or (some? ext) (exclude? filename))
                (str filename " does not have a mime-type"))))))))

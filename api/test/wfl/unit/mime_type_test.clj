(ns wfl.unit.mime-type-test
  (:require [clojure.test        :refer [deftest is testing]]
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
  (let [exclude? #{"gs://broad-gotc-dev-wfl-ptc-test-inputs/sarscov2_illumina_full/outputs/call-demux_deplete/demux_deplete/955c74b3-854a-4075-839f-856d0f41e020/call-sra_meta_prep/write_lines_bc5302b8fdd987961b17ced77e1da4ab.tmp"}
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

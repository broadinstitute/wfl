(ns wfl.unit.mime-type-test
  (:require [clojure.test        :refer [deftest is testing]]
            [wfl.mime-type       :as mime-type]
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

(defn ^:private get-files
  "Return a list of files contained in the workflow parameters"
  [parameter-type parameter-value]
  (let [type-env (fn [type]
                   (->> (:objectFieldNames type)
                        (map #(-> {(-> % :fieldName keyword) (:fieldType %)}))
                        (into {})))]
    ((fn go [type value]
       (case (:typeName type)
         "Array"
         (letfn [(go-elem [x] (go (:arrayType type) x))]
           (mapcat go-elem value))
         "File"
         [value]
         "Object"
         (let [name->type (type-env type)]
           (mapcat (fn [[k v]] (go (name->type k) v)) value))
         "Optional"
         (when value (go (:optionalType type) value))
         []))
     parameter-type parameter-value)))

(deftest test-workflow-mime-types
  (let [exclude? #{"gs://fc-ac04311b-a7a7-4613-b9e9-2c7e1ed3fa72/345157e0-a58f-4f39-8242-d735ef04694f/sarscov2_illumina_full/813a9346-6592-47a5-94ba-5b1d05aa21f4/call-demux_deplete/demux_deplete/955c74b3-854a-4075-839f-856d0f41e020/call-sra_meta_prep/write_lines_bc5302b8fdd987961b17ced77e1da4ab.tmp"}
        cases [[(workflows/read-resource "assemble-refbased-outputs")
                (-> "assemble-refbased-description" workflows/read-resource :outputs)]
               [(workflows/read-resource "sarscov2-illumina-full-outputs")
                (-> "sarscov2-illumina-full-description" workflows/read-resource :outputs)]]]
    (doseq [[values description] cases]
      (let [type (workflows/make-object-type description)]
        (doseq [filename (get-files type values)]
          (let [ext (mime-type/ext-mime-type-no-default filename)]
            (is (or (some? ext) (exclude? filename))
                (str filename " does not have a mime-type"))))))))

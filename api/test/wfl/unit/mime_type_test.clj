(ns wfl.unit.mime-type-test
  (:require [clojure.test        :refer [deftest is testing]]
            [wfl.mime-type       :as mime-type]
            [wfl.tools.workflows :as workflows]))

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
  (let [cases [[(workflows/read-resource "assemble-refbased-outputs")
                (-> "assemble-refbased-description" workflows/read-resource :outputs)]]]
    (doseq [[values description] cases]
      (let [type (workflows/make-object-type description)]
        (doseq [filename (get-files type values)]
          (is (some? (mime-type/ext-mime-type filename))
              (str filename " does not have a mime-type")))))))

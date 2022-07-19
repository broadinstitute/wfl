(ns wfl.mime-type
  (:require [ring.util.mime-type :as mime-type]
            [wfl.util            :as util]))

(def ^:private mime-types
  "mime-types for file extensions commonly used in computational biology."
  (let [extensions
        {"bam"     "application/octet-stream"
         "cram"    "application/octet-stream"
         "fasta"   "application/octet-stream"
         "genbank" "application/octet-stream"
         "ready"   "text/plain"
         "tsv"     "text/tab-separated-values"
         "vcf"     "text/plain"}]
    (merge mime-type/default-mime-types extensions)))

;; visible for testing
(defn ext-mime-type-no-default
  "Look up the mime-type of `filename` by file extension."
  [filename]
  (loop [filename (util/basename filename)]
    (when-let [ext (util/extension filename)]
      (or (mime-types ext) (recur (util/remove-extension filename))))))

(defn ext-mime-type
  "Look up the mime-type of `filename` by file extension."
  [filename]
  (or (ext-mime-type-no-default filename) "application/octet-stream"))

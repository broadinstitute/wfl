(ns wfl.mime-type
  (:require [clojure.string :as str]
            [ring.util.mime-type :as mime-type]))

(def ^:private mime-types
  "mime-types for file extensions commonly used in computational biology."
  (let [bio-mimes {"bam"   "application/octet-stream"
                   "cram"  "application/octet-stream"
                   "fasta" "application/octet-stream"
                   "vcf"   "text/plain"}]
    (merge mime-type/default-mime-types bio-mimes)))

(defn ext-mime-type
  "Look up the mime-type of the filename by file extension."
  [filename]
  (if-let [ext (last (str/split filename #"\."))]
    (mime-types ext)))

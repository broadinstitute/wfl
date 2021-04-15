(ns wfl.tsv
  "Read and write tab-separated value files for Terra."
  (:refer-clojure :exclude [read])
  (:require [clojure.data.json :as json]
            [clojure.java.io   :as io]
            [clojure.string    :as str])
  (:import [java.io Reader StringReader StringWriter Writer]))

(defprotocol TsvField
  "One field of a row or line in a .tsv file."
  (-write [field ^Writer out]
    "Write FIELD to java.io.Writer OUT as a .tsv value.")
  (-read [^Reader in]
    "Read one field from java.io.Reader IN as a .tsv value."))

(extend-protocol TsvField
  clojure.lang.Named
  (-write [field out] (.write out (name field)))
  java.lang.Object
  (-write [field out] (.write out (json/write-str field)))
  java.lang.String
  (-write [field out] (.write out field)))

(defn write-fields
  "Write the sequence of .tsv FIELDS to WRITER."
  [fields ^Writer writer]
  (letfn [(write [field] (-write field writer))]
    (loop [fields fields separator ""]
      (when-first [field fields]
        (write separator)
        (write field)
        (recur (rest fields) "\t")))))

(defn read-field
  "Read a .tsv field from the string S."
  [s]
  (or (try (json/read-str s)
           (catch Throwable _ignored))
      s))

(defn read-fields
  "Return .tsv fields from a string S of tab-separated fields."
  [s]
  (map read-field (str/split s #"\t")))

(defn read
  "Read a .tsv table, a sequence of sequences, from READER."
  [^Reader reader]
  (-> reader io/reader line-seq
      (->> (map read-fields))))

(defn read-str
  "Read a .tsv table, a sequence of sequences, from string S."
  [s]
  (read (StringReader. s)))

(defn read-file
  "Return a lazy .tsv table from FILE."
  [file]
  (letfn [(lines [reader] (lazy-seq (if-let [line (.readLine reader)]
                                      (cons line (lines reader))
                                      (.close reader))))]
    (map read-fields (lines (io/reader file)))))

(defn write
  "Write TABLE, a sequence of .tsv field sequences, to WRITER."
  [table ^Writer writer]
  (when-first [line table]
    (write-fields line writer)
    (.write writer "\n")
    (recur (rest table) writer)))

(defn write-str
  "Return TABLE, a sequence of .tsv field sequences, in a string."
  [table]
  (let [writer (StringWriter.)]
    (write table writer)
    (.toString writer)))

(defn write-file
  "Write TABLE, a sequence of .tsv field sequences, to FILE."
  [table file]
  (with-open [writer (io/writer file)]
    (write table writer)))

(def edn
  [{:integer 5
    :keyword :keyword
    :map     {:a "map"}
    :set     #{:a "set" 5}
    :string  "a frayed knot"
    :vector1 ["one thing"]
    :vector2 ["and" 23 "skiddoo"]}
   {:integer 23
    :keyword :lockletter
    :map     {:b "map"}
    :set     #{:b "set" 23}
    :string  "mutant text"
    :vector1 ["or another thing"]
    :vector2 ["and" 23 "skiddoo" :fnord]}])

(defn mapulate
  "Return a sequence of maps from TABLE, a sequence of sequences."
  [table]
  (let [[header & rows] table]
    (map (partial zipmap header) rows)))

(defn tabulate
  "Return a table, sequence of sequences, from EDN, a sequence of maps."
  [edn]
  (let [header (keys (first edn))]
    (letfn [(extract [row] (map row header))]
      (cons header (map extract edn)))))

(def table (tabulate edn))
(mapulate table)

(mapulate (read-str (write-str table)))

(write-file (read-file "/Users/tbl/Broad/wfl/assemblies.tsv")
            "/Users/tbl/Broad/wfl/assemblies-tbl.tsv")

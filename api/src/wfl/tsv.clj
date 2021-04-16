(ns wfl.tsv
  "Read and write Terra's tab-separated value (.tsv) files."
  (:refer-clojure :exclude [read])
  (:require [clojure.data.json :as json]
            [clojure.java.io   :as io]
            [clojure.string    :as str])
  (:import [java.io Reader StringReader StringWriter Writer]))

(defprotocol TsvField
  "One field of a row or line in a .tsv file."
  (-write [field ^Writer out]
    "Write FIELD to java.io.Writer OUT as a .tsv value."))

(extend-protocol TsvField
  nil
  (-write [field out] (.write out "NULL"))
  clojure.lang.Named
  (-write [field out] (.write out (name field)))
  java.lang.Object
  (-write [field out] (.write out (json/write-str field)))
  java.lang.String
  (-write [field out] (.write out field)))

(defn ^:private write-fields
  "Write the sequence of .tsv FIELDS to WRITER."
  [fields ^Writer writer]
  (loop [fields fields separator ""]
    (when-first [field fields]
      (-write separator writer)
      (-write field writer)
      (recur (rest fields) "\t"))))

(defn ^:private read-field
  "Read a .tsv field from the string S."
  [s]
  (case (first s)
    (\[ \{) (json/read-str s)
    s))

(defn ^:private read-fields
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

(defn ^:private assert-mapulatable!
  "Throw when (mapulate TABLE) would lose information."
  [table]
  (letfn [(repeated? [[label n]] (when-not (== 1 n) label))]
    (let [repeats (keep repeated? (frequencies (first table)))]
      (when (seq repeats)
        (throw (ex-info "TABLE.tsv has repeated column labels."
                        {:repeats repeats})))))
  (let [counts (set (map count table))]
    (when-not (== 1 (count counts))
      (throw (ex-info "TABLE.tsv has multiple column counts."
                      {:counts counts})))))

(defn mapulate
  "Return a sequence of maps from TABLE, a sequence of sequences."
  [table]
  (assert-mapulatable! table)
  (let [[header & rows] table]
    (map (partial zipmap header) rows)))

(defn ^:private assert-tabulatable!
  "Throw when (tabulate EDN) would lose information."
  [edn]
  (let [counts (set (map count edn))]
    (when-not (== 1 (count counts))
      (throw (ex-info "TABLE.tsv has multiple column counts."
                      {:counts counts})))))

(defn tabulate
  "Return a table, sequence of sequences, from EDN, a sequence of maps."
  [edn]
  (assert-tabulatable! edn)
  (let [header (keys (first edn))]
    (letfn [(extract [row] (map row header))]
      (cons header (map extract edn)))))

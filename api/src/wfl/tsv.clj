(ns wfl.tsv
  "Read and write tab-separated value files for Terra."
  (:require [clojure.data.csv  :as csv]
            [clojure.data.json :as json]
            [clojure.edn       :as edn]
            [clojure.java.io   :as io]
            [clojure.string    :as str]
            [wfl.debug         :as debug])
  (:import [java.io File Reader StringReader StringWriter Writer]))


"https://api.firecloud.org/api/workspaces/wfl-dev/SARSCoV2-Illumina-Full/exportAttributesTSV"
"https://api.firecloud.org/"
"/api/workspaces/{workspaceNamespace}/{workspaceName}/exportAttributesTSV"

(defprotocol TsvField
  "One field of a row or line in a .tsv file."
  (-write [field ^Writer out]
    "Write FIELD to java.io.Writer OUT as a .tsv value.")
  (-read [^Reader in]
    "Read one field from java.io.Reader IN as a .tsv value."))

(defprotocol TsvLine
  "One row or line in a .tsv file."
  (-write [line ^Writer out]
    "Write LINE to java.io.Writer OUT as a .tsv line.")
  (-read [^Reader in]
    "Read one line from java.io.Reader IN as a .tsv line."))

(defprotocol TsvFile
  "A complete .tsv file."
  (-write [file ^Writer out]
    "Write FILE to java.io.Writer OUT as a .tsv file.")
  (-read [^Reader in]
    "Read a file from java.io.Reader IN as a .tsv file."))

(extend-protocol TsvField
  #_#_nil
  (-write [field out] (.write out ""))
  clojure.lang.Named
  (-write [field out] (.write out (name field)))
  java.lang.Object
  (-write [field out] (.write out (json/write-str field)))
  java.lang.String
  (-write [field out] (.write out field)))

(let [out (StringWriter.)]
  (-write "fnord" out)
  (.toString out))

(let [out (StringWriter.)]
  (-write {:a 0} out)
  (.toString out))

#_(let [out (StringWriter.)]
    (-write nil out)
    (.toString out))

(defn read-str
  [tsv]
  (doall (map (partial map json/read-str)
              (csv/read-csv tsv :separator \tab))))

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

(def table
  (let [columns (keys (first edn))
        rows    (map (apply juxt columns) edn)]
    (map (partial map json/write-str) (cons columns rows))))

(defn write-str
  [table]
  (-> table
      (->> (map (partial str/join \tab))
           (str/join \newline))
      (str \newline)))

(defn write
  [table writer]
  (with-open []))

(print (write-str table))

(let [#_#_tsv     (write-str table)
      #_#_tsv-in  (with-open [in (io/reader (char-array tsv-out))]
                    (doall (map #(str/split % #"\t") (line-seq in))))
      #_#_tsv-in  (with-open [in (io/reader (char-array tsv-out))]
                    #_(debug/trace (csv/read-csv in)))]
  #_(read-str tsv)
  
  )

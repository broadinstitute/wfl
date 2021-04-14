(ns wfl.tsv
  "Read and write tab-separated value files for Terra."
  (:require [clojure.data.csv  :as csv]
            [clojure.data.json :as json]
            [clojure.edn       :as edn]
            [clojure.java.io   :as io]
            [clojure.string    :as str]
            [wfl.debug         :as debug])
  (:import [java.io File StringWriter]))


"https://api.firecloud.org/api/workspaces/wfl-dev/SARSCoV2-Illumina-Full/exportAttributesTSV"
"https://api.firecloud.org/"
"/api/workspaces/{workspaceNamespace}/{workspaceName}/exportAttributesTSV"

(let [edn     [{:integer 5
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
                :vector2 ["and" 23 "skiddoo" :fnord]}]
      columns (keys (first edn))
      rows    (map (apply juxt columns) edn)
      table   (map (partial map json/write-str) (cons columns rows))
      _ (debug/trace table)
      tsv-out (with-open [out (StringWriter.)]
                (csv/write-csv out table)
                (.toString (.getBuffer out)))
      _ (debug/trace tsv-out)
      #_#_tsv-in  (with-open [in (io/reader (char-array tsv-out))]
                    (let [[columns & rows] (csv/read-csv in :separator \tab)
                          labels (map keyword columns)]
                      (doall (map (partial zipmap labels) rows))))]
  (println tsv-out)
  tsv-out
  )


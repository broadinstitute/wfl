(ns wfl.tsv-test
  "Test the wfl.tsv utility namespace."
  (:require [clojure.test   :refer [deftest is testing]]
            [clojure.data   :as data]
            [clojure.pprint :refer [pprint]]
            [clojure.set    :as set]
            [clojure.walk   :as walk]
            [wfl.debug      :as debug]
            [wfl.tsv        :as tsv])
  (:import [java.util UUID]))

(def ^:private edn
  "More EDN than can be represented in a Terra (.tsv) file."
  [{:boolean    false
    :integer    5
    :keyword    :keyword
    :map        {:a "map"}
    :set        #{:a "set" 5}
    :string     "a frayed knot"
    :vector     ["one thing"]
    :jason-rose ["and" 23 "skiddoo"]}
   {:boolean    true
    :integer    23
    :keyword    :lockletter
    :map        {:b "map"}
    :set        #{:b "set" 23}
    :string     "mutant text"
    :vector     ["or another thing"]
    :jason-rose ["and" 23 "skiddoo" :fnord]}])

(defn ^:private tsvify
  "Make TREE safe for Terra's TSV format."
  [tree]
  (letfn [(tsvify [x] (cond (boolean? x) (if   x "true" "false")
                            (keyword? x) (name x)
                            (number?  x) (str  x)
                            (set?     x) (vec  x)
                            :else              x))]
    (walk/postwalk tsvify tree)))

(deftest round-trip-table-map
  (testing "Map to table transformation does not lose information."
    (is (= edn (tsv/mapulate (tsv/tabulate edn))))
    (let [keywords (keys (first edn))
          renames  (zipmap keywords (map name keywords))]
      (letfn [(rename [m] (set/rename-keys m renames))]
        (let [keywordless (map rename edn)]
          (is (= keywordless (tsv/mapulate (tsv/tabulate keywordless)))))))))

(deftest manipulate-throws
  (testing "TABLE.tsv has multiple column counts."
    (is (thrown-with-msg?
         Throwable #"TABLE.tsv has multiple column counts."
         (tsv/tabulate [{:a 0} {:a 0 :b 1}]))))
  (testing "TABLE.tsv has repeated column labels."
    (is (thrown-with-msg?
         Throwable #"TABLE.tsv has repeated column labels."
         (tsv/mapulate [[:a :a] [:b :b]]))))
  (testing "TABLE.tsv has multiple column counts."
    (is (thrown-with-msg?
         Throwable #"TABLE.tsv has multiple column counts."
         (tsv/mapulate (first [[:a :b] [:b]]))))))

(deftest round-trip-file
  (testing "Round-trip EDN to file.tsv and back."
    (let [file  (str (UUID/randomUUID) ".tsv")
          table (tsv/tabulate edn)]
      (tsv/write-file table file)
      (let [tsv (tsv/read-file file)]
        (debug/trace (data/diff table tsv))))))

(deftest round-trip-string
  (testing "Round-trip EDN to string and back."
    (letfn [(reset [m] (update m "set" set))]
      (let [in    (tsvify edn)
            table (tsv/tabulate edn)
            out   (tsv/mapulate (tsv/read-str (tsv/write-str table)))]
        (debug/trace (data/diff (map reset in) (map reset out)))
        (is (= in out))))))

(tsv/write-file
 (tsv/read-file "/Users/tbl/Broad/wfl/assemblies.tsv")
 "/Users/tbl/Broad/wfl/assemblies-tbl.tsv")

(tsv/write-file
 (tsv/read-file
  "/Users/tbl/Broad/wfl/SARSCoV2-Illumina-Full-workspace-attributes.tsv")
 "/Users/tbl/Broad/wfl/SARSCoV2-Illumina-Full-workspace-attributes-tbl.tsv")

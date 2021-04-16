(ns wfl.tsv-test
  "Test the wfl.tsv utility namespace."
  (:require [clojure.test :refer [deftest is testing]]
            [clojure.set  :as set]
            [wfl.debug    :as debug]
            [wfl.tsv      :as tsv]))

(def edn
  "More EDN than can be represented in a Terra (.tsv) file."
  [{:boolean false
    :integer 5
    :keyword :keyword
    :map     {:a "map"}
    :nil     nil
    :set     #{:a "set" 5}
    :string  "a frayed knot"
    :vector1 ["one thing"]
    :vector2 ["and" 23 "skiddoo"]}
   {:boolean true
    :integer 23
    :keyword :lockletter
    :map     {:b "map"}
    :nil     (not nil)
    :set     #{:b "set" 23}
    :string  "mutant text"
    :vector1 ["or another thing"]
    :vector2 ["and" 23 "skiddoo" :fnord]}])

(deftest round-trip-table-map
  (testing "Map to table transformation does not lose information."
    (is (= edn (tsv/mapulate (tsv/tabulate edn))))
    (let [keywords (keys (first edn))
          renames  (zipmap keywords (map name keywords))]
      (letfn [(rename [m] (set/rename-keys m renames))]
        (let [keywordless (map rename edn)]
          (is (= keywordless (tsv/mapulate (tsv/tabulate keywordless)))))))))

;; (tsv/mapulate (tsv/read-str (tsv/write-str table)))

(tsv/write-file (tsv/read-file "/Users/tbl/Broad/wfl/assemblies.tsv")
                "/Users/tbl/Broad/wfl/assemblies-tbl.tsv")

(ns wfl.tsv-test
  "Test the wfl.tsv utility namespace."
  (:require [clojure.test :refer [deftest is testing]]
            [clojure.set  :as set]
            [wfl.debug    :as debug]
            [wfl.tsv      :as tsv]))

(def edn
  "More EDN than can be represented in a Terra (.tsv) file."
  [{:boolean    false
    :integer    5              
    :keyword    :keyword       
    :map        {:a "map"}     
    :nil        nil            
    :set        #{:a "set" 5}  
    :string     "a frayed knot"
    :vector     ["one thing"]
    :jason-rose ["and" 23 "skiddoo"]}
   {:boolean    true                
    :integer    23                  
    :keyword    :lockletter         
    :map        {:b "map"}          
    :nil        (not nil)           
    :set        #{:b "set" 23}      
    :string     "mutant text"       
    :vector1    ["or another thing"]
    :jason-rose ["and" 23 "skiddoo" :fnord]}])

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

;; (tsv/mapulate (tsv/read-str (tsv/write-str table)))

(tsv/write-file (tsv/read-file "/Users/tbl/Broad/wfl/assemblies.tsv")
                "/Users/tbl/Broad/wfl/assemblies-tbl.tsv")

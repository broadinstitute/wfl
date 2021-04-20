(ns wfl.unit.tsv-test
  "Test the wfl.tsv utility namespace."
  (:require [clojure.test        :refer [deftest is testing]]
            [clojure.java.io     :as io]
            [clojure.set         :as set]
            [clojure.walk        :as walk]
            [wfl.tools.resources :as resources]
            [wfl.tsv             :as tsv])
  (:import [java.util UUID]))

(def ^:private the-edn
  "More EDN than can be represented in a Terra (.tsv) file."
  [{:boolean    false
    :integer    23
    :keyword    :keyword
    :map        {:a "map"}
    :set        #{:a "set" 5}
    :string     "a frayed knot"
    :vector     ["one thing"]
    :jason-rose [:made "a" :good "joke"]}
   {:boolean    true
    :integer    5
    :keyword    :lockletter
    :map        {:b "map"}
    :set        #{:b "set" 23}
    :string     "23 skiddoo"
    :vector     ["or another thing"]
    :jason-rose ["and" 23 "skiddoo" :fnord]}])

(deftest round-trip-table-map
  (testing "Map to table does not lose information."
    (is (= the-edn (tsv/mapulate (tsv/tabulate the-edn))))
    (let [keywords (keys (first the-edn))
          renames  (zipmap keywords (map name keywords))]
      (letfn [(rename [m] (set/rename-keys m renames))]
        (let [keywordless (map rename the-edn)]
          (is (= keywordless (tsv/mapulate (tsv/tabulate keywordless)))))))))

(deftest manipulators-throw
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
         (tsv/mapulate [[:a :b] [:b]])))))

(defn ^:private hack-terrafy
  "Make TREE safe for Terra's TSV format."
  [tree]
  (letfn [(terrify [x] (cond (boolean? x) (if   x "true" "false")
                             (keyword? x) (name x)
                             (set?     x) (vec  x)
                             :else              x))]
    (walk/postwalk terrify tree)))

(defn ^:private hack-unterrafy
  "Recover sets in THE-EDN from the resulting Terra TSV map."
  [tsv]
  (letfn [(fix? [[k v]] (when (set? v) k))]
    (let [set-keys (map name (keep fix? (first the-edn)))]
      (letfn [(up-set [m k] (update m k set))
              (re-set [m] (into {} (map (partial up-set m) set-keys)))]
        (map re-set tsv)))))

(deftest round-trip-string
  (testing "Round-trip THE-EDN to string and back."
    (is (= (-> the-edn
               hack-terrafy
               hack-unterrafy)
           (-> the-edn
               tsv/tabulate
               tsv/write-str
               tsv/read-str
               tsv/mapulate
               hack-unterrafy)))))

(deftest round-trip-file
  (testing "Round-trip THE-EDN to file.tsv and back."
    (let [file (io/file (str (UUID/randomUUID) ".tsv"))]
      (try (is (= (-> the-edn hack-terrafy hack-unterrafy)
                  (do (-> the-edn tsv/tabulate (tsv/write-file file))
                      (-> file tsv/read-file tsv/mapulate hack-unterrafy))))
           (finally (io/delete-file file))))))

(deftest round-trip-inputs
  (testing "Round-trip inputs.edn to TSV string and back."
    (let [file   "sarscov2_illumina_full/inputs.edn"
          inputs [(resources/read-resource file)]]
      (is (= (-> inputs hack-terrafy)
             (-> inputs
                 tsv/tabulate
                 tsv/write-str
                 tsv/read-str
                 tsv/mapulate))))))

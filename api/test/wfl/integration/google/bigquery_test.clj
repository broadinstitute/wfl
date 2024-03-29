(ns wfl.integration.google.bigquery-test
  (:require [clojure.test :refer [deftest is]]
            [wfl.service.google.bigquery :as bigquery]))

(def ^:private google-project "broad-jade-dev-data")
(def ^:private dr-dataset "datarepo_zerotest_partition")
(def ^:private dr-view "sample")

(deftest test-list-datasets
  (let [datasets (bigquery/list-datasets google-project)]
    (is (< 0 (count datasets)) "No dataset found!")
    (is (every? #(= (:kind %) "bigquery#dataset") datasets))))

(deftest test-list-tables
  (let [tables (bigquery/list-tables google-project dr-dataset)]
    (is (< 0 (count tables)) "No tables/views found!")
    (is (every? #(= (:kind %) "bigquery#table") tables))))

(deftest test-query-table-sync
  (is (->> (format "SELECT * FROM `%s.%s.%s`" google-project dr-dataset dr-view)
           (bigquery/query-sync google-project)
           :rows seq)
      "No results found!"))

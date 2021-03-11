(ns wfl.unit.google.bigquery-test
  (:require [clojure.test :refer :all]
            [clojure.data.csv :as csv]
            [wfl.service.google.bigquery :as bigquery]))

(def ^:private dr-view-content
  {:kind "bigquery#queryResponse",
   :schema {:fields [{:name "datarepo_row_id", :type "STRING", :mode "NULLABLE"}
                     {:name "vcf", :type "STRING", :mode "NULLABLE"}
                     {:name "id", :type "STRING", :mode "NULLABLE"}
                     {:name "vcf_index", :type "STRING", :mode "NULLABLE"}]},
   :jobReference {:projectId "broad-jade-dev-data", :jobId "job_Zd6Ld4uCl8kmuFkiGKsPdk5OnBNP", :location "US"},
   :totalRows "2",
   :rows [{:f [{:v "8d529c08-bc21-4ea0-9254-d99b9c12dfd2"}
               {:v "drs://jade.datarepo-dev.broadinstitute.org/v1_f1c765c6-5446-4aef-bdbe-c741ff09c27c_f2a7d885-4fd3-4faf-bd16-06219a8eef99"}
               {:v "wfl-test-a830fe00-7ef2-430a-9d5e-fa0c18dc99e1/"}
               {:v "drs://jade.datarepo-dev.broadinstitute.org/v1_f1c765c6-5446-4aef-bdbe-c741ff09c27c_2b67ed53-ccac-49c6-8ad6-8952a1dfaf98"}]}
          {:f [{:v "8d529c08-bc21-4ea0-9254-d99b9c12dfd2"}
               {:v "drs://jade.datarepo-dev.broadinstitute.org/v1_f1c765c6-5446-4aef-bdbe-c741ff09c27c_f2a7d885-4fd3-4faf-bd16-06219a8eef99"}
               {:v "wfl-test-a830fe00-7ef2-430a-9d5e-fa0c18dc99e1/"}
               {:v "drs://jade.datarepo-dev.broadinstitute.org/v1_f1c765c6-5446-4aef-bdbe-c741ff09c27c_2b67ed53-ccac-49c6-8ad6-8952a1dfaf98"}]}],
   :totalBytesProcessed "221025",
   :jobComplete true,
   :cacheHit false})

(deftest test-parse-row
  (testing "Can parse row information given rows content"
    (is (= (read-string (:totalRows dr-view-content))
           (count (map bigquery/parse-row (:rows dr-view-content)))))))

(deftest test-flatten-rows
  (testing "Can parse and flatten rows given bigquery query result"
    (is (= (* (read-string (:totalRows dr-view-content))
              (count (get-in dr-view-content [:schema :fields])))
           (count (bigquery/flatten-rows dr-view-content))))))

(deftest test-dump-table->tsv
  (testing "Dumping from BigQuery table response to TSV works"
    (deftest test-dump-table->tsv
      (let [terra-table-name "test-name"
            contents (-> (bigquery/dump-table->tsv dr-view-content "test-name")
                         slurp
                         (csv/read-csv :separator \tab))]
        (is (= (format "entity:%s_id" terra-table-name) (first (first contents))) "The result TSV header is not properly formatted!")))))

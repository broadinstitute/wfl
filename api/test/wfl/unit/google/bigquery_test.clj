(ns wfl.unit.google.bigquery-test
  (:require [clojure.test :refer :all]
            [clojure.data.csv :as csv]
            [wfl.service.google.bigquery :as bigquery]))

(def ^:private dr-view-content
  "Test fixture to simulate data structure from bigquery/query-sync."
  {:kind "bigquery#queryResponse"
   :schema {:fields [{:mode "NULLABLE" name "datarepo_row_id" :type "STRING"}
                     {:mode "NULLABLE" :name "vcf" :type "STRING"}
                     {:mode "NULLABLE" :name "id" :type "STRING"}
                     {:mode "NULLABLE" :name "vcf_index" :type "STRING"}]}
   :jobReference {:projectId "broad-jade-dev-data" :jobId "job_Zd6Ld4uCl8kmuFkiGKsPdk5OnBNP" :location "US"}
   :totalRows "2"
   :rows ['("8d529c08-bc21-4ea0-9254-d99b9c12dfd2"
            "drs://jade.datarepo-dev.broadinstitute.org/v1_f1c765c6-5446-4aef-bdbe-c741ff09c27c_f2a7d885-4fd3-4faf-bd16-06219a8eef99"
            "wfl-test-a830fe00-7ef2-430a-9d5e-fa0c18dc99e1/"
            "drs://jade.datarepo-dev.broadinstitute.org/v1_f1c765c6-5446-4aef-bdbe-c741ff09c27c_2b67ed53-ccac-49c6-8ad6-8952a1dfaf98")
          '("8d529c08-bc21-4ea0-9254-d99b9c12dfd2"
            "drs://jade.datarepo-dev.broadinstitute.org/v1_f1c765c6-5446-4aef-bdbe-c741ff09c27c_f2a7d885-4fd3-4faf-bd16-06219a8eef99"
            "wfl-test-a830fe00-7ef2-430a-9d5e-fa0c18dc99e1/"
            "drs://jade.datarepo-dev.broadinstitute.org/v1_f1c765c6-5446-4aef-bdbe-c741ff09c27c_2b67ed53-ccac-49c6-8ad6-8952a1dfaf98")]
   :totalBytesProcessed "221025"
   :jobComplete true
   :cacheHit false})

(deftest test-dump-table->tsv
  (testing "Dumping from BigQuery table response to TSV works"
    (let [terra-table-name "test-name"
          contents (-> (bigquery/dump-table->tsv dr-view-content "test-name")
                       (csv/read-csv :separator \tab))]
      (is (= (format "entity:%s_id" terra-table-name)
             (first (first contents))) "The result TSV header is not properly formatted!"))))

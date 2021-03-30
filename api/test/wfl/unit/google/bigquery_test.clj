(ns wfl.unit.google.bigquery-test
  (:require [clojure.test :refer :all]
            [clojure.data.csv :as csv]
            [wfl.service.google.bigquery :as bigquery]))

;; mock output from bigquery/query-sync
(def ^:private dr-view-content
  {:schema {:fields [{:mode "NULLABLE" :name "datarepo_row_id" :type "STRING"}
                     {:mode "NULLABLE" :name "vcf" :type "STRING"}
                     {:mode "NULLABLE" :name "id" :type "STRING"}
                     {:mode "NULLABLE" :name "vcf_index" :type "STRING"}]}
   :rows [["8d529c08-bc21-4ea0-9254-d99b9c12dfd2"
           "drs://jade.datarepo-dev.broadinstitute.org/v1_f1c765c6-5446-4aef-bdbe-c741ff09c27c_f2a7d885-4fd3-4faf-bd16-06219a8eef99"
           "wfl-test-a830fe00-7ef2-430a-9d5e-fa0c18dc99e1/"
           "drs://jade.datarepo-dev.broadinstitute.org/v1_f1c765c6-5446-4aef-bdbe-c741ff09c27c_2b67ed53-ccac-49c6-8ad6-8952a1dfaf98"]
          ["8d529c08-bc21-4ea0-9254-d99b9c12dfd2"
           "drs://jade.datarepo-dev.broadinstitute.org/v1_f1c765c6-5446-4aef-bdbe-c741ff09c27c_f2a7d885-4fd3-4faf-bd16-06219a8eef99"
           "wfl-test-a830fe00-7ef2-430a-9d5e-fa0c18dc99e1/"
           "drs://jade.datarepo-dev.broadinstitute.org/v1_f1c765c6-5446-4aef-bdbe-c741ff09c27c_2b67ed53-ccac-49c6-8ad6-8952a1dfaf98"]]})

(deftest test-dump-table->tsv
  (let [terra-table-name "test-name"
        contents (-> (bigquery/dump-table->tsv dr-view-content "test-name")
                     (csv/read-csv :separator \tab)
                     doall)]
    (is (= 3 (count contents)) "expect 3 rows (headers + 2 of data)")
    (is (= 5 (count (first contents))) "first row has additional column for the entity")
    (is (every? #(= 4 %) (map count (rest contents))) "data rows have same number of columns as fields")
    (is (= (format "entity:%s_id" terra-table-name)
           (ffirst contents) "The result TSV header is not properly formatted!"))))

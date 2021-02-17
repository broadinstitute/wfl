(ns wfl.service.google.bigquery
  "Wrappers for Google Cloud BigQuery REST APIs.
   See https://cloud.google.com/bigquery/docs/reference/rest."
  (:require [clj-http.client :as http]
            [clojure.data.csv :as csv]
            [clojure.java.io :as io]
            [clojure.data.json :as json]
            [clojure.string :as str]
            [wfl.auth :as auth]
            [wfl.util         :as util])
  (:import [java.io StringWriter]))

(def ^:private bigquery-url
  "The BigQuery REST API URL."
  (partial str "https://bigquery.googleapis.com/bigquery/v2/"))

(defn list-datasets
  "List all datasets under Google Cloud PROJECT.

   Parameters
   ----------
   project  - Google Cloud Project to list the BigQuery datasets in.

   Example
   -------
     (list-datasets \"broad-jade-dev-data\")"
  [project]
  (-> (str/join "/" ["projects" project "datasets"])
      bigquery-url
      (http/get {:headers (auth/get-auth-header)})
      util/response-body-json
      :datasets))

;; TODO: we may need to support pagination on this query
(defn list-tables
  "List all tables/views under Google Cloud PROJECT.

   Parameters
   ----------
   project  - Google Cloud Project to list the BigQuery datasets in.
   dataset  - Dataset of the tables/views to list.

   Example
   -------
     (list-tables \"broad-jade-dev-data\" \"zerosnapshot\")"
  [project dataset]
  (-> (str/join "/" ["projects" project "datasets" dataset "tables"])
      bigquery-url
      (http/get {:headers (auth/get-auth-header)})
      util/response-body-json
      :tables))

(def list-views "Function alias for views." list-tables)

(defn query-table-sync
  "Query for a BigQuery TABLE within a Data Repo SNAPSHOT in
   Google Cloud PROJECT synchronously. Using non-legacy query
   SQL syntax. Note: table and views are interchangeable BigQuery
   concepts here, so both of them work as `TABLE`.

   At least `data custodian` permission on the snapshot is
   required for running the query job.

   Parameters
   ----------
   project  - Google Cloud Project to list the BigQuery datasets in.
   snapshot - Data Repo Snapshot.
   table    - BigQuery table/view name.

   Example
   -------
     (query-table-sync \"broad-jade-dev-data\" \"zerosnapshot\" \"sample\")"
  [project snapshot table]
  (let [query (format "SELECT * FROM `%s.%s.%s`" project snapshot table)]
    (-> (str/join "/" ["projects" project "queries"])
        bigquery-url
        (http/post {:headers (auth/get-auth-header)
                    :body    (json/write-str
                              {:query query
                               :use_legacy_sql false})})
        util/response-body-json)))

(def query-view-sync "Function alias for views." query-table-sync)

(defn dump-table->tsv
  "Dump a BigQuery TABLE/view into a tsv FILE that's supported by Terra.
   Will dump the tsv contents to bytes if no FILE is provided.

   The first row header must follow the format 'entity:{data_table}_id'.
   For example, 'entity:sample_id' will upload the tsv data into a `sample`
   table in the workspace (or create one if it does not exist). If the
   table already contains a sample with that id, it will get overwritten.

   Parameters
   ----------
   table            - BigQuery table/view name.
   terra-data-table - The `table` name in the Terra workspace to import the TSV.
   file             - [optional] TSV file name to dump.

   Example
   -------

     (dump-table->tsv table \"datarepo_row\" \"dumped.tsv\")
     (dump-table->tsv table \"datarepo_row\")"
  ([table terra-data-table file]
   (letfn [(parse-row [row] (map :v (:f row)))
           (format-header-for-terra [header]
             (cons (format "entity:%s_id" terra-data-table) (rest header)))]
     (let [headers (map :name (get-in table [:schema :fields]))
           rows (map parse-row (get-in table [:rows]))
           contents (-> []
                        (into [(format-header-for-terra headers)])
                        (into rows))]
       (with-open [writer (io/writer file)]
         (csv/write-csv writer
                        contents
                        :separator \tab)
         file))))
  ([table terra-data-table]
   (-> (dump-table->tsv table terra-data-table (StringWriter.))
       .toString .getBytes)))

(def dump-view->tsv "Function alias for views." dump-table->tsv)

(comment
  (let [table {:kind "bigquery#queryResponse",
               :schema {:fields [{:name "datarepo_row_id", :type "STRING", :mode "NULLABLE"}
                                 {:name "vcf", :type "STRING", :mode "NULLABLE"}
                                 {:name "id", :type "STRING", :mode "NULLABLE"}
                                 {:name "vcf_index", :type "STRING", :mode "NULLABLE"}]},
               :jobReference {:projectId "broad-jade-dev-data", :jobId "job_Zd6Ld4uCl8kmuFkiGKsPdk5OnBNP", :location "US"},
               :totalRows "1",
               :rows [{:f [{:v "8d529c08-bc21-4ea0-9254-d99b9c12dfd2"}
                           {:v "drs://jade.datarepo-dev.broadinstitute.org/v1_f1c765c6-5446-4aef-bdbe-c741ff09c27c_f2a7d885-4fd3-4faf-bd16-06219a8eef99"}
                           {:v "wfl-test-a830fe00-7ef2-430a-9d5e-fa0c18dc99e1/"}
                           {:v "drs://jade.datarepo-dev.broadinstitute.org/v1_f1c765c6-5446-4aef-bdbe-c741ff09c27c_2b67ed53-ccac-49c6-8ad6-8952a1dfaf98"}]}],
               :totalBytesProcessed "221025",
               :jobComplete true,
               :cacheHit false}]
    (dump-table->tsv table "datarepo_row" "samples.tsv")))

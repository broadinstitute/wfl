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
   dataset  - Dataset of the tables/views to list."
  [project dataset]
  (-> (str/join "/" ["projects" project "datasets" dataset "tables"])
      bigquery-url
      (http/get {:headers (auth/get-auth-header)})
      util/response-body-json
      :tables))

(defn ^:private normalize-table [{:keys [schema] :as response}]
  (let [repeated? (mapv #(= "REPEATED" (:mode %)) (:fields schema))]
    (letfn [(flatten-column [idx column]
              (if (repeated? idx) (mapv :v (:v column)) (:v column)))
            (flatten-row [row] (vec (map-indexed flatten-column (:f row))))]
      (update response :rows #(map flatten-row %)))))

(defn query-sync
  "Given QUERY, look for rows in a BigQuery table within a
   Google Cloud PROJECT synchronously, using non-legacy query
   SQL syntax. Return flatten rows.

   Parameters
   ----------
   project  - Google Cloud Project to list the BigQuery datasets in.
   query    - BigQuery Standard SQL query string."
  [project query]
  (-> (str/join "/" ["projects" project "queries"])
      bigquery-url
      (http/post {:headers (auth/get-auth-header)
                  :body    (json/write-str {:query          query
                                            :use_legacy_sql false})})
      util/response-body-json
      normalize-table))

(defn dump-table->tsv
  "Dump a BigQuery TABLE/view into a tsv FILE that's supported by Terra.
   Will dump the tsv contents to bytes if no FILE is provided.

   The first column of the table must be the table primary key,
   i.e. [entity-type]_id. For example, 'entity:sample_id' will upload the tsv
   data into a `sample` table in the workspace (or create one if it does not
   exist). If the table already contains a sample with that id, it will get
   overwritten.

   Parameters
   ----------
   table            - BigQuery table/view body with the rows field flatten.
   file             - [optional] TSV file name to dump.

   Example
   -------
     (dump-table->tsv table \"dumped.tsv\")
     (dump-table->tsv table)"
  ([table file]
   (letfn [(format-entity-type [[head & rest]]
             (cons (format "entity:%s" head) rest))]
     (let [columns (map :name (get-in table [:schema :fields]))]
       (with-open [writer (io/writer file)]
         (csv/write-csv writer [(format-entity-type columns)] :separator \tab)
         (csv/write-csv writer (:rows table) :separator \tab)
         file))))
  ([table]
   (str (dump-table->tsv table (StringWriter.)))))

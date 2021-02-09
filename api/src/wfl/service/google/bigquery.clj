(ns wfl.service.google.bigquery
  "Wrappers for Google Cloud BigQuery REST APIs.
   See https://cloud.google.com/bigquery/docs/reference/rest."
  (:require [clojure.data.json :as json]
            [clojure.string :as str]
            [clj-http.client :as http]
            [wfl.util         :as util]
            [wfl.once :as once]))

(defn ^:private json-body [response]
  (-> response :body (or "null") util/parse-json))

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
      (bigquery-url)
      (http/get {:headers (once/get-auth-header)})
      json-body
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
    (bigquery-url)
    (http/get {:headers (once/get-auth-header)})
    json-body
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
    (bigquery-url)
    (http/post {:headers (once/get-auth-header)
                :body    (json/write-str
                           {:query query
                            :use_legacy_sql false})})
    json-body)))

(def query-view-sync "Function alias for views." query-table-sync)


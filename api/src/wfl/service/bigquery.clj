(ns wfl.service.bigquery
  "Interact with GCloud Biqquery service."
  (:require [clojure.data.json :as json]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [clj-http.client :as http]
            [wfl.environments :as env]
            [wfl.util         :as util]
            [wfl.wfl         :as wfl]
            [wfl.once :as once])
  (:import [com.google.cloud.bigquery BigQuery
            BigQueryOptions
            JobId
            JobInfo
            QueryJobConfiguration
            BigQuery$DatasetListOption]
           [java.util ArrayList Collections Random UUID]))

(defn service
  "Build a BigQuery service instance from Google Cloud PROJECT-ID."
  [project-id]
  (let [credentials (once/service-account-credentials)]
    (-> (BigQueryOptions/newBuilder)
        (.setCredentials credentials)
        (.setProjectId project-id)
        .build .getService)))

(comment
  (service "broad-jade-dev-data"))

(defn list-datasets
  "Return a list of all datasets under Google Cloud PROJECT-ID."
  [project-id]
  (letfn [(conj-dataset [coll dataset]
            (conj coll (.getDataset (.getDatasetId dataset))))]
    (let [service          (service project-id)
          dataset-iterator (-> service
                               (.listDatasets (into-array BigQuery$DatasetListOption []))
                               .iterateAll)]
      (reduce conj-dataset [] dataset-iterator))))

(comment
  (list-datasets "broad-jade-dev-data"))

(defn query-snapshot
  "Query for a Data Repo dataset SNAPSHOT in BigQuery given PROJECT_ID."
  [project-id snapshot]
  (let [service (service project-id)
        query (format "SELECT * FROM `%s.%s.sample" project-id snapshot)
        query-config (-> (QueryJobConfiguration/newBuilder) .build)]
    (.query service query-config)))

(comment
  (query-snapshot "broad-jade-dev-data" "zerosnapshot"))
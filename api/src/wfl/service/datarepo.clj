(ns wfl.service.datarepo
  "Do stuff in the data repo."
  (:require [clj-http.client             :as http]
            [clojure.data.json           :as json]
            [clojure.string              :as str]
            [wfl.auth                    :as auth]
            [wfl.environment             :as env]
            [wfl.mime-type               :as mime-type]
            [wfl.service.google.bigquery :as bigquery]
            [wfl.util                    :as util])
  (:import (java.time Instant)
           (java.util.concurrent TimeUnit)))

(defn ^:private datarepo-url [& parts]
  (let [url (util/de-slashify (env/getenv "WFL_TDR_URL"))]
    (str/join "/" (cons url parts))))

(def ^:private repository
  "API URL for Data Repo API."
  (partial datarepo-url "api/repository/v1"))

(defn ^:private get-repository-json [& parts]
  (-> (apply repository parts)
      (http/get {:headers (auth/get-auth-header)})
      util/response-body-json))

(defn dataset
  "Query the DataRepo for the Dataset with `dataset-id`."
  [dataset-id]
  {:pre [(some? dataset-id)]}
  (get-repository-json "datasets" dataset-id))

(defn ^:private ingest
  "Ingest THING to DATASET-ID according to BODY."
  [thing dataset-id body]
  (-> (repository "datasets" dataset-id thing)
      (http/post {:content-type :application/json
                  :headers      (auth/get-service-account-header)
                  :body         (json/write-str body :escape-slash false)})
      util/response-body-json
      :id))

;; While TDR does assign `loadTag`s, they're not always unique - submitting
;; requests in parallel can cause bad things to happen. Use this to create a
;; unique `loadTag` instead.
(defn ^:private new-load-tag []
  (str "workflow-launcher:" (Instant/now)))

(defn ingest-file
  "Ingest `source` file as `target` using `dataset-id` and `profile-id`."
  [dataset-id profile-id source target]
  (ingest "files" dataset-id {:description (util/basename source)
                              :loadTag     (new-load-tag)
                              :mime_type   (mime-type/ext-mime-type source)
                              :profileId   profile-id
                              :source_path source
                              :target_path target}))

(defn bulk-ingest
  "Ingest `source` file as `target` using `dataset-id` and `profile-id`."
  [dataset-id profile-id source->target]
  (letfn [(make-file-load [source target]
            {:description (util/basename source)
             :mimeType    (mime-type/ext-mime-type source)
             :sourcePath  source
             :targetPath  target})]
    (ingest
     "files/bulk/array"
     dataset-id
     {:profileId          profile-id
      :loadArray          (map #(apply make-file-load %) source->target)
      :loadTag            (new-load-tag)
      :maxFailedFileLoads 0})))

(defn ingest-table
  "Ingest TABLE at PATH to DATASET-ID and return the job ID."
  [dataset-id path table]
  (ingest
   "ingest"
   dataset-id
   {:format          "json"
    :load_tag        (new-load-tag)
    :max_bad_records 0
    :path            path
    :table           table}))

(defn poll-job
  "Poll the job with `job-id` every `seconds` [default: 5] and return its
   result."
  ([job-id seconds]
   (let [result   #(get-repository-json "jobs" job-id "result")
         running? #(-> (get-repository-json "jobs" job-id)
                       :job_status
                       #{"running"})]
     (while (running?) (.sleep TimeUnit/SECONDS seconds))
     (result)))
  ([job-id]
   (poll-job job-id 5)))

(defn create-dataset
  "Create a dataset with EDN `dataset-request` and return the id
   of the created dataset. See `DatasetRequestModel` in the
   DataRepo swagger page for more information.
   https://jade.datarepo-dev.broadinstitute.org/swagger-ui.html#/"
  [dataset-request]
  (-> (repository "datasets")
      (http/post {:content-type :application/json
                  :headers      (auth/get-service-account-header)
                  :body         (json/write-str
                                 dataset-request
                                 :escape-slash false)})
      util/response-body-json
      :id
      poll-job
      :id))

(defn delete-dataset
  "Delete the dataset with `dataset-id`."
  [dataset-id]
  (-> (repository "datasets" dataset-id)
      (http/delete {:headers (auth/get-service-account-header)})
      util/response-body-json
      :id
      poll-job))

;; Note the TDR is under active development, the endpoint spec is getting
;; changed so the spec in this function is not consistent with the TDR Swagger
;; page in order to make the request work.
;; See also https://cloud.google.com/bigquery/docs/reference/standard-sql/migrating-from-legacy-sql
(defn create-snapshot
  "Return snapshot-id when the snapshot defined by `snapshot-request` is ready.
   See `SnapshotRequestModel` in the DataRepo swagger page for more information.
   https://jade.datarepo-dev.broadinstitute.org/swagger-ui.html#/"
  [snapshot-request]
  (-> (repository "snapshots")
      (http/post {:headers      (auth/get-service-account-header)
                  :content-type :application/json
                  :form-params  snapshot-request})
      util/response-body-json
      :id
      poll-job
      :id))

(defn list-snapshots
  "Return snapshots optionally filtered by source dataset, where dataset-ids
   identify the source datasets. Hard-coded to return 999 pages for now.

   Parameters
   ----------
   dataset-ids      - Optionally filter the result snapshots
                      where provided datasets are source datasets.

   Example
   -------
     (list-snapshots)
     (list-snapshots \"48a51f71-6bab-483d-a270-3f9ebfb241cd\")"
  [& dataset-ids]
  (letfn [(maybe-merge [m k v] (if (seq v) (assoc m k {:datasetIds v}) m))]
    (-> (http/get (repository "snapshots")
                  (maybe-merge {:headers (auth/get-service-account-header)
                                :query-params {:limit 999}}
                               :query-params dataset-ids))
        util/response-body-json)))

(defn delete-snapshot
  "Delete the Snapshot with `snapshot-id`."
  [snapshot-id]
  (-> (repository "snapshots" snapshot-id)
      (http/delete {:headers (auth/get-service-account-header)})
      util/response-body-json
      :id
      poll-job))

(defn snapshot
  "Return the snapshot with `snapshot-id`."
  [snapshot-id]
  (get-repository-json "snapshots" snapshot-id))

(defn all-columns
  "Return all of the columns of `table` in `dataset` content."
  [dataset table]
  (->> (get-in dataset [:schema :tables])
       (filter #(= (:name %) table))
       first
       :columns))

;; Note TDR uses snapshot names as unique identifier so the
;; name must be unique among snapshots.
(defn make-snapshot-request
  "Return a snapshot request for `row-ids`and `columns` from `table` name
   in `_dataset`."
  [{:keys [name defaultProfileId description] :as _dataset} columns table row-ids]
  (let [row-ids (vec row-ids)]
    {:contents    [{:datasetName name
                    :mode        "byRowId"
                    :rowIdSpec   {:tables   [{:columns columns
                                              :rowIds	 row-ids
                                              :tableName table}]}}]
     :description description
     :name        name
     :profileId   defaultProfileId}))

;; hack - TDR adds the "datarepo_" prefix to the dataset name in BigQuery
;; They plan to expose this name via `GET /api/repository/v1/datasets/{id}`
;; in a future release.
(defn ^:private bigquery-name
  "Return the BigQuery name of the `dataset-or-snapshot`."
  [{:keys [name] :as dataset-or-snapshot}]
  (letfn [(snapshot? [x] (util/absent? x :defaultSnapshotId))]
    (if (snapshot? dataset-or-snapshot) name (str "datarepo_" name))))

(defn ^:private query-table-impl
  ([{:keys [dataProject] :as dataset} table col-spec]
   (let [bq-name (bigquery-name dataset)]
     (->> (format "SELECT %s FROM `%s.%s.%s`" col-spec dataProject bq-name table)
          (bigquery/query-sync dataProject)))))

(defn query-table
  "Query everything or optionally the `columns` in `table` in the Terra DataRepo
  `dataset`, where `dataset` is a DataRepo dataset or a snapshot of a dataset."
  ([dataset table]
   (query-table-impl dataset table "*"))
  ([dataset table columns]
   (->> (util/to-comma-separated-list (map name columns))
        (query-table-impl dataset table))))

(defn ^:private query-table-between-impl
  [{:keys [dataProject] :as dataset} table between [start end] col-spec]
  (let [[table between] (map name [table between])
        bq-name (bigquery-name dataset)
        query   (str/join \newline ["SELECT %s"
                                    "FROM `%s.%s.%s`"
                                    "WHERE %s BETWEEN '%s' AND '%s'"])]
    (-> query
        (format col-spec dataProject bq-name table between start end)
        wfl.debug/trace
        (->> (bigquery/query-sync dataProject)))))

(defn query-table-between
  "Return rows from `table` of `dataset`, where `dataset` can name a
  snapshot and values in the `between` column fall within `interval`.
  Select `columns` from matching rows when specified.  A 400 response
  means no rows matched the query."
  ([dataset table between interval]
   (query-table-between-impl dataset table between interval "*"))
  ([dataset table between interval columns]
   (->> (map name columns)
        util/to-comma-separated-list
        (query-table-between-impl dataset table between interval))))

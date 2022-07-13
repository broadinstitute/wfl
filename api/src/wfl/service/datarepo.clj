(ns wfl.service.datarepo
  "Do stuff in the data repo."
  (:require [clj-http.client             :as http]
            [clojure.data.json           :as json]
            [clojure.spec.alpha          :as s]
            [clojure.string              :as str]
            [wfl.auth                    :as auth]
            [wfl.environment             :as env]
            [wfl.mime-type               :as mime-type]
            [wfl.service.google.bigquery :as bigquery]
            [wfl.util                    :as util])
  (:import [clojure.lang ExceptionInfo]
           [java.time Instant]
           [wfl.util UserException]))

;; Specs
(s/def ::loadTag string?)

(def final?
  "The final statuses a data repo job can have."
  #{"succeeded" "failed"})

(def active?
  "The statuses an active data repo job can have."
  #{"running"})

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

;; By default, this endpoint retrieves all dataset elements
;; except for ACCESS_INFORMATION,
;; which is needed to construct BigQuery paths.
;; Explicitly include all dataset elements.
;; https://data.terra.bio/swagger-ui.html#/repository/retrieveDataset
;;
(defn datasets
  "Query the DataRepo for the Dataset with `dataset-id`."
  [dataset-id]
  (try
    (let [include ["ACCESS_INFORMATION"
                   "DATA_PROJECT"
                   "PROFILE"
                   "SCHEMA"
                   "STORAGE"]]
      (-> (repository "datasets" dataset-id)
          (http/get {:headers      (auth/get-auth-header)
                     :query-params {:include include}})
          util/response-body-json))
    (catch ExceptionInfo e
      (throw
       (UserException. "Cannot access dataset"
                       {:dataset dataset-id :status (-> e ex-data :status)}
                       e)))))

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
  "Ingest TABLE at PATH with LOAD-TAG to DATASET-ID.
  Return the job ID."
  ([dataset-id path table load-tag]
   (ingest "ingest" dataset-id {:format          "json"
                                :load_tag        load-tag
                                :max_bad_records 0
                                :path            path
                                :table           table}))
  ([dataset-id path table]
   (ingest-table dataset-id path table (new-load-tag))))

(defn poll-job
  "Poll the job with `job-id` every `seconds` [default: 5] and return its
   result with `max-attempts` [default: 20]."
  [job-id & [seconds max-attempts]]
  (let [done? #(-> (get-repository-json "jobs" job-id) :job_status final?)]
    (util/poll done? (or seconds 5) (or max-attempts 20))
    (get-repository-json "jobs" job-id "result")))

(defn job-metadata
  "Return the metadata of job with `job-id` when done."
  [job-id]
  {:pre [(some? job-id)]}
  (get-repository-json "jobs" job-id))

(defn job-result
  "Return the result of job with `job-id`."
  [job-id]
  (get-repository-json "jobs" job-id "result"))

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

(defn create-snapshot-job
  "Return snapshot creation job-id defined by `snapshot-request` right away.
   See `SnapshotRequestModel` in the DataRepo swagger page for more information.
   https://jade.datarepo-dev.broadinstitute.org/swagger-ui.html#/"
  [snapshot-request]
  (-> (repository "snapshots")
      (http/post {:headers      (auth/get-service-account-header)
                  :content-type :application/json
                  :form-params  snapshot-request})
      util/response-body-json
      :id))

;; Note the TDR is under active development, the endpoint spec is getting
;; changed so the spec in this function is not consistent with the TDR Swagger
;; page in order to make the request work.
;; See also https://cloud.google.com/bigquery/docs/reference/standard-sql/migrating-from-legacy-sql
(defn create-snapshot
  "Return snapshot-id when the snapshot defined by `snapshot-request` is ready.
   See `SnapshotRequestModel` in the DataRepo swagger page for more information.
   https://jade.datarepo-dev.broadinstitute.org/swagger-ui.html#/"
  [snapshot-request]
  (-> snapshot-request
      create-snapshot-job
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
    (util/response-body-json
     (http/get (repository "snapshots")
               (maybe-merge {:headers (auth/get-service-account-header)
                             :query-params {:limit 999}}
                            :query-params dataset-ids)))))

(defn delete-snapshot
  "Delete the Snapshot with `snapshot-id`."
  [snapshot-id]
  (-> (repository "snapshots" snapshot-id)
      (http/delete {:headers (auth/get-service-account-header)})
      util/response-body-json
      :id
      poll-job))

;; By default, this endpoint retrieves all snapshot elements
;; except for ACCESS_INFORMATION,
;; which is needed to construct BigQuery paths.
;; Explicitly include all snapshot elements.
;; https://data.terra.bio/swagger-ui.html#/repository/retrieveSnapshot
;;
(defn snapshot
  "Return the snapshot with `snapshot-id`."
  [snapshot-id]
  (let [include ["ACCESS_INFORMATION"
                 "DATA_PROJECT"
                 "PROFILE"
                 "SOURCES"
                 "TABLES"]]
    (-> (apply repository ["snapshots" snapshot-id])
        (http/get {:headers      (auth/get-auth-header)
                   :query-params {:include include}})
        util/response-body-json)))

(defn snapshot-url
  "Return a link to `snapshot` in TDR UI."
  [{:keys [id] :as _snapshot}]
  (datarepo-url "snapshots" id))

(defn delete-dataset-snapshots
  "Delete snapshots on dataset with `dataset-id`."
  [dataset-id]
  (letfn [(delete [{:keys [id] :as _snapshot}]
            (-> (repository "snapshots" id)
                (http/delete {:headers (auth/get-service-account-header)})
                util/response-body-json :id))]
    (->> dataset-id list-snapshots :items
         (map delete)   doall
         (map poll-job) doall)))

(defn delete-snapshots-then-dataset
  "Delete snapshots on dataset with `dataset-id` then delete it."
  [dataset-id]
  (delete-dataset-snapshots dataset-id)
  (delete-dataset dataset-id))

(defn all-columns
  "Helper function to parse all of the columns
   of `table` in `dataset` body."
  [dataset table]
  (->> (get-in dataset [:schema :tables])
       (filter #(= (:name %) table))
       first
       :columns))

;; Note TDR uses snapshot names as unique identifier so the
;; name must be unique among snapshots.
(defn make-snapshot-request
  "Return a snapshot request for `row-ids` and `columns` from `table` name
   in `_dataset`."
  [{:keys [name defaultProfileId description] :as _dataset} columns table row-ids]
  {:contents    [{:datasetName name
                  :mode        "byRowId"
                  :rowIdSpec   {:tables [{:columns   columns
                                          :rowIds    row-ids
                                          :tableName table}]}}]
   :description description
   :name        (str name "_" table)
   :profileId   defaultProfileId})

(defn ^:private bq-path
  "Return the fully-qualified path to `table-name` in BigQuery."
  [{:keys [accessInformation] :as _dataset-or-snapshot} table-name]
  (let [projectId   (get-in accessInformation [:bigQuery :projectId])
        datasetName (get-in accessInformation [:bigQuery :datasetName])]
    (format "%s.%s.%s" projectId datasetName table-name)))

(defn ^:private query-table-impl
  [{:keys [dataProject] :as dataset-or-snapshot} table col-spec]
  (-> "SELECT %s FROM `%s`"
      (format col-spec (bq-path dataset-or-snapshot table))
      (->> (bigquery/query-sync dataProject))))

(defn query-table
  "Query everything or optionally the `columns` in `table` in the Terra DataRepo
  `dataset`, where `dataset` is a DataRepo dataset or a snapshot of a dataset."
  ([dataset table]
   (query-table-impl dataset table "*"))
  ([dataset table columns]
   (query-table-impl dataset table
                     (util/to-comma-separated-list (map name columns)))))

(def ^:private metadata-table-name-prefix "datarepo_row_metadata_")

(defn metadata
  "Return TDR row metadata table name corresponding to `table-name`."
  [table-name]
  (format "%s%s" metadata-table-name-prefix table-name))

(defn ^:private query-metadata-table-impl
  "Return `col-spec` from rows from TDR `dataset`.`table` metadata
  falling in `interval` and ingested with `loadTag` if specified."
  [{:keys [dataProject]        :as dataset-or-snapshot}
   table
   {:keys [ingestTime loadTag] :as _filters}
   col-spec]
  (let [meta-table        (metadata table)
        [start end]       ingestTime
        where-ingest-time (when (and start end)
                            (format "ingest_time BETWEEN '%s' AND '%s'"
                                    start end))
        where-load-tag    (when loadTag
                            (format "load_tag = '%s'" loadTag))
        where-clauses     (util/remove-empty-and-join
                           [where-ingest-time where-load-tag] " AND ")
        where             (if (empty? where-clauses) ""
                              (format "WHERE %s" where-clauses))]
    (-> "SELECT %s FROM `%s` %s"
        (format col-spec (bq-path dataset-or-snapshot meta-table) where)
        (->> (bigquery/query-sync dataProject)))))

(defn query-metadata-table
  "Return `columns` in rows from metadata `table` in `dataset-or-snapshot`
  matching specified `filters`.
  A 400 response means no rows matched the query. "
  ([dataset-or-snapshot table filters]
   (query-metadata-table-impl dataset-or-snapshot table filters "*"))
  ([dataset-or-snapshot table filters columns]
   (let [col-spec (util/to-comma-separated-list (map name columns))]
     (query-metadata-table-impl dataset-or-snapshot table filters col-spec))))

;; utilities

(defn ^:private id-and-name [{:keys [id name] :as _dataset}]
  (util/make-map id name))

(defn throw-unless-column-exists
  "Throw if `table` does not have `column` in `dataset`."
  [table column dataset]
  (when (->> table :columns (filter (comp #{column} :name)) empty?)
    (throw (UserException. "Column not found"
                           {:column  column
                            :table   table
                            :dataset (id-and-name dataset)}))))

(defn table-or-throw
  "Throw or return the table with `table-name` in `dataset`."
  [table-name {:keys [schema] :as dataset}]
  (let [[table & _] (filter (comp #{table-name} :name) (:tables schema))]
    (when-not table
      (throw (UserException. "Table not found"
                             {:table   table-name
                              :dataset (id-and-name dataset)})))
    table))

(defn metadata-table-path-or-throw
  "Throw or return the path to `dataset`.`table-name`'s row metadata table
   in BigQuery."
  [table-name {:keys [dataProject] :as dataset}]
  (let [tables-path          (bq-path dataset "__TABLES__")
        metadata-name        (metadata table-name)
        metadata-path        (bq-path dataset metadata-name)
        metadata-found?      (-> "SELECT * FROM `%s` WHERE table_id = '%s'"
                                 (format tables-path metadata-name)
                                 (->> (bigquery/query-sync dataProject))
                                 :totalRows
                                 Integer/parseInt
                                 (> 0))
        not-found-msg        (str/join \space ["TDR row metadata table not found"
                                               "in BigQuery at"
                                               metadata-path])]
    (when-not metadata-found?
      (throw (UserException. not-found-msg
                             {:table          table-name
                              :metadata-table metadata-name
                              :metadata-path  metadata-path
                              :dataset        (id-and-name dataset)})))
    metadata-path))

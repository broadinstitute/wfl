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
  (:import [clojure.lang ExceptionInfo]
           [java.time Instant]
           [wfl.util UserException]))

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

(defn datasets
  "Query the DataRepo for the Dataset with `dataset-id`."
  [dataset-id]
  (try
    (get-repository-json "datasets" dataset-id)
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
  "Ingest TABLE at PATH to DATASET-ID and return the job ID."
  [dataset-id path table]
  (ingest "ingest" dataset-id {:format          "json"
                               :load_tag        (new-load-tag)
                               :max_bad_records 0
                               :path            path
                               :table           table}))

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

(defn get-job-result
  "Get the result of job with `job-id`."
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

(defn snapshot
  "Return the snapshot with `snapshot-id`."
  [snapshot-id]
  (get-repository-json "snapshots" snapshot-id))

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
  "Return a snapshot request for `row-ids`and `columns` from `table` name
   in `_dataset`."
  [{:keys [name defaultProfileId description] :as _dataset} columns table row-ids]
  {:contents    [{:datasetName name
                  :mode        "byRowId"
                  :rowIdSpec   {:tables [{:columns   columns
                                          :rowIds    row-ids
                                          :tableName table}]}}]
   :description description
   :name        name
   :profileId   defaultProfileId})

;; HACK: (str "datarepo_" name) is a hack while accessInformation is nil.
;;
(defn ^:private bq-datasetId-dataProject
  "Return a BigQuery [datasetId dataProject] pair for `dataset-or-snapshot`."
  [dataset-or-snapshot]
  (wfl.debug/trace dataset-or-snapshot)
  (cond (:defaultSnapshostId dataset-or-snapshot)
        (let [{:keys [accessInformation dataProject name] :as _dataset}
              dataset-or-snapshot]
          [(or (get-in accessInformation [:bigQuery :datasetId])
               (str "datarepo_" name))
           dataProject])
        (map? dataset-or-snapshot)
        (let [{:keys [dataProject name] :as _snapshot} dataset-or-snapshot]
          [name dataProject])
        (string? dataset-or-snapshot)
        (let [{:keys [accessInformation dataProject name] :as _dataset}
              (datasets dataset-or-snapshot)]
          [(or (get-in accessInformation [:bigQuery :datasetId])
               (str "datarepo_" name))
           dataProject])
        :else
        (throw (UserException. "Not dataset or snapshot" dataset-or-snapshot))))

(defn ^:private query-table-impl
  [dataset-or-snapshot table col-spec]
  (wfl.debug/trace dataset-or-snapshot)
  (let [[datasetId dataProject] (bq-datasetId-dataProject dataset-or-snapshot)]
    (wfl.debug/trace [datasetId dataProject])
    (-> "SELECT %s FROM `%s.%s.%s`"
        (format col-spec dataProject datasetId table)
        (->> (bigquery/query-sync dataProject)))))

(defn query-table
  "Query everything or optionally the `columns` in `table` in the Terra DataRepo
  `dataset`, where `dataset` is a DataRepo dataset or a snapshot of a dataset."
  ([dataset table]
   (query-table-impl dataset table "*"))
  ([dataset table columns]
   (query-table-impl dataset table
                     (util/to-comma-separated-list (map name columns)))))

(defn ^:private query-table-between-impl
  [dataset-or-snapshot table between [start end] col-spec]
  (wfl.debug/trace dataset-or-snapshot)
  (let [[datasetId dataProject] (bq-datasetId-dataProject dataset-or-snapshot)
        [table between] (map name [table between])]
    (wfl.debug/trace [datasetId dataProject])
    (-> (str/join \newline ["SELECT %s FROM `%s.%s.%s`"
                            "WHERE %s BETWEEN '%s' AND '%s'"])
        (format col-spec dataProject datasetId table between start end)
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

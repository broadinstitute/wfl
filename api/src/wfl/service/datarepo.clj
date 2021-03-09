(ns wfl.service.datarepo
  "Do stuff in the data repo."
  (:require [clj-http.client   :as http]
            [clojure.data.json :as json]
            [wfl.auth          :as auth]
            [wfl.environment   :as env]
            [wfl.mime-type     :as mime-type]
            [wfl.util          :as util])
  (:import (java.time Instant)
           (java.util.concurrent TimeUnit)))

(defn ^:private datarepo-url [& parts]
  (let [url (util/slashify (env/getenv "WFL_TDR_URL"))]
    (apply str url parts)))

(def ^:private repository
  "API URL for Data Repo API."
  (partial datarepo-url "api/repository/v1/"))

(defn dataset
  "Query the DataRepo for the Dataset with `dataset-id`."
  [dataset-id]
  (-> (repository "datasets/" dataset-id)
      (http/get {:headers (auth/get-service-account-header)})
      util/response-body-json))

(defn ^:private ingest
  "Ingest THING to DATASET-ID according to BODY."
  [thing dataset-id body]
  (-> (repository (format "datasets/%s/%s" dataset-id thing))
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
   {:format                "json"
    :load_tag              "string"
    :max_bad_records       0
    :path                  path
    :table                 table}))

(defn poll-job
  "Return result for JOB-ID in ENVIRONMENT when it stops running."
  [job-id]
  (let [get-result #(-> (repository "jobs/" job-id "/result")
                        (http/get {:headers (auth/get-service-account-header)})
                        util/response-body-json)
        running?   #(-> (repository "jobs/" job-id)
                        (http/get {:headers (auth/get-service-account-header)})
                        util/response-body-json
                        :job_status
                        #{"running"})]
    (while (running?) (.sleep TimeUnit/SECONDS 1))
    (get-result)))

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
  "Delete the Dataset with `dataset-id`."
  [dataset-id]
  (-> (repository "datasets/" dataset-id)
      (http/delete {:headers (auth/get-service-account-header)})
      util/response-body-json
      :id
      poll-job))

(defn create-snapshot
  "Create a snapshot from standard SQL query,
   assert or row-ids, based on `snapshot-request`.

   See `SnapshotRequestModel` in the
   DataRepo swagger page for more information.
   https://jade.datarepo-dev.broadinstitute.org/swagger-ui.html#/

   Note the TDR is under active development,
   the endpoint spec is getting changed so the
   spec in this function is not consistent with
   the TDR Swagger page in order to make the
   request work."
  [snapshot-request]
  (-> (repository "snapshots")
      (http/post {:headers      (auth/get-service-account-header)
                  :content-type :application/json
                  :form-params  snapshot-request})
      util/response-body-json
      :id
      poll-job))

(defn list-snapshots
  "List all Snapshots the caller has access to.
   Hard-coded to return 999 pages for now.

   Parameters
   ----------
   dataset-ids      - Optionally filter the result snapshots
                      where provided datasets are source datasets.

   Example
   -------
     (list-snapshots)
     (list-snapshots \"48a51f71-6bab-483d-a270-3f9ebfb241cd\" \"85efdfea-52fb-4698-bee6-eef76104a7f4\")"
  [& dataset-ids]
  (letfn [(maybe [m k v] (if (seq v) (assoc m k {:datasetIds v}) m))]
    (-> (http/get (repository "snapshots")
                  (-> {:headers (auth/get-service-account-header)
                       :query-params {:limit 999}}
                      (maybe :query-params dataset-ids)))
        util/response-body-json)))

(defn delete-snapshot
  "Delete the Snapshot with `snapshot-id`."
  [dataset-id]
  (-> (repository "snapshots/" dataset-id)
      (http/delete {:headers (auth/get-service-account-header)})
      util/response-body-json
      :id
      poll-job))

(defn snapshot
  "Query the DataRepo for the Snapshot with `snapshot-id`."
  [snapshot-id]
  (-> (repository "snapshots/" snapshot-id)
      (http/get {:headers (auth/get-service-account-header)})
      util/response-body-json))

;; visible for testing
(defn compose-create-snapshot-query-payload
  "Helper function for composing snapshot payload from `dataset` and `table`,
   given a date range specified by exclusive `start` and inclusive `end`.

   Parameters
   ----------
   _dataset   - Dataset information response from TDR.
   table      - Name of the table in the dataset schema to query from.
   start      - The start date object in the timeframe to query exclusively.
   end        - The end date object in the timeframe to query inclusively.

   Example
   -------
   (compose-create-snapshot-query-payload
    {:name             \"sarscov2_illumina_full_inputs\"
     :description      \"COVID-19 sarscov2_illumina_full pipeline inputs\",
     :defaultProfileId \"390e7a85-d47f-4531-b612-165fc977d3bd\"}
    \"sarscov2_illumina_full_inputs\"
    (java.util.Date.)
    (java.util.Date.))"
  [{:keys [name defaultProfileId description] :as _dataset} table start end]
  ;; FIXME: BigQuery supports parameterized queries, but how can we prevent SQL Injection here?
  (let [select-from (->> [name table]
                         cycle
                         (take 4)
                         (apply format "SELECT %s.%s.datarepo_row_id FROM %s.%s"))
        where       (format "WHERE (%s.%s.datarepo_ingest_date > '%tF' AND %s.%s.datarepo_ingest_date <= '%tF')"
                            name table start name table end)
        query       (format "%s %s" select-from where)]
    {:contents    [{:datasetName name
                    :mode        "byQuery"
                    :querySpec   {:assetName "sample_asset"
                                  :query     query}}]
     :description description
     :name        name
     :profileId   defaultProfileId}))

(comment
  (dataset "28dbedad-ca6b-4a4a-bd9a-b351b5be3617")

  (-> (dataset "28dbedad-ca6b-4a4a-bd9a-b351b5be3617")
      (compose-create-snapshot-query-payload "sarscov2_illumina_full_inputs" (util/str->date "2021-01-01") (java.util.Date.))
      create-snapshot)

  (list-snapshots "85efdfea-52fb-4698-bee6-eef76104a7f4")

  (snapshot "b79a0a92-f100-4120-b371-3662439e59f8")

  (delete-snapshot "b79a0a92-f100-4120-b371-3662439e59f8"))
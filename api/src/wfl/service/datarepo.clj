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
  (let [url (util/slashify (env/getenv "WFL_TERRA_DATA_REPO_URL"))]
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
    :ignore_unknown_values true
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

(comment
  (delete-snapshot "2450996e-3a9b-4311-9aa2-82bacb8f17bd")
  )

(defn snapshot
  "Query the DataRepo for the Snapshot with `snapshot-id`."
  [snapshot-id]
  (-> (repository "snapshots/" snapshot-id)
    (http/get {:headers (auth/get-service-account-header)})
    util/response-body-json))

(comment
  (snapshot "2450996e-3a9b-4311-9aa2-82bacb8f17bd")
  )

(comment
  (dataset "85efdfea-52fb-4698-bee6-eef76104a7f4")
  (let [snapshot-request {:contents  [{:datasetName "zerotest_partition"
                                       :mode        "byQuery"
                                       :querySpec   {:assetName "sample_asset"
                                                     :query     "SELECT zerotest_partition.sample.datarepo_row_id FROM zerotest_partition.sample"}}]
                          :description "Rex test snapshot by query"
                          :name      "zerotest_partition_snapshot_by_query"
                          :profileId "390e7a85-d47f-4531-b612-165fc977d3bd"}]
    (create-snapshot snapshot-request))
  (list-snapshots "85efdfea-52fb-4698-bee6-eef76104a7f4"))

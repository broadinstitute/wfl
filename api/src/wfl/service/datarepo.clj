(ns wfl.service.datarepo
  "Do stuff in the data repo."
  (:require [clojure.data.json :as json]
            [clj-http.client :as http]
            [wfl.once :as once]
            [wfl.util :as util]
            [clojure.string :as str])
  (:import [org.apache.http HttpException]
           (java.util.concurrent TimeUnit)))

(def ^:private datarepo-url
  (let [url (-> (System/getenv "TERRA_DATA_REPO_URL")
                (or "https://jade.datarepo-dev.broadinstitute.org/")
                util/slashify)]
    (partial str url)))

(def ^:private repository
  "API URL for Data Repo API."
  (partial datarepo-url "api/repository/v1/"))

(defn delete-dataset
  "Create a dataset with EDN `schema`."
  [schema]
  (-> (repository "datasets")
      (http/post {:content-type :application/json
                  :headers      (once/get-service-account-header)
                  :body         (json/write-str schema :escape-slash false)})
      util/response-body-json))

(defn ^:private ingest
  "Ingest THING to DATASET-ID according to BODY."
  [thing dataset-id body]
  (-> (repository (format "datasets/%s/%s" dataset-id thing))
      (http/post {:content-type :application/json
                  :headers      (once/get-service-account-header)
                  :body         (json/write-str body :escape-slash false)})
      util/response-body-json
      :id))

(defn ingest-file
  "Ingest `source` file as `target` using `dataset-id` and `profile-id`."
  ([dataset-id profile-id source target options]
   (-> options
       (util/assoc-when util/absent? :description (util/basename source))
       (util/assoc-when util/absent? :mime_type "text/plain")
       (merge {:profileId profile-id :source_path source :target_path target})
       (->> (ingest "files" dataset-id))))
  ([dataset-id profile-id source target]
   (ingest-file dataset-id profile-id source target {})))

(defn ingest-dataset
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
                        (http/get {:headers (once/get-service-account-header)})
                        util/response-body-json)
        running?   #(-> (repository "jobs/" job-id)
                        (http/get {:headers (once/get-service-account-header)})
                        util/response-body-json
                        :job_status
                        #{"running"})]
    (while (running?) (.sleep TimeUnit/SECONDS 1))
    (get-result)))

(defn create-dataset
  "Create a dataset with EDN `schema`."
  [dataset-request]
  (-> (repository "datasets")
      (http/post {:content-type :application/json
                  :headers      (once/get-service-account-header)
                  :body         (json/write-str
                                 dataset-request
                                 :escape-slash false)})
      util/response-body-json
      :id
      poll-job
      :id))

(defn delete-dataset
  "Create a dataset with EDN `schema`."
  [dataset-id]
  (-> (repository "datasets/" dataset-id)
      (http/delete {:headers (once/get-service-account-header)})
      util/response-body-json
      :id
      poll-job))

(comment
  (def successful-file-ingest-response
    {:description     "something derived from file name + extension?"
     :path            "/zero-test/NA12878_PLUMBING.g.vcf.gz"
     :directoryDetail nil
     :collectionId    "f359303e-15d7-4cd8-a4c7-c50499c90252"
     :fileDetail      {:datasetId "f359303e-15d7-4cd8-a4c7-c50499c90252"
                       :mimeType  "text/plain"
                       :accessUrl "gs://broad-jade-dev-data-bucket/f359303e-15d7-4cd8-a4c7-c50499c90252/271cd32c-2e86-4f46-9eb1-f3ddb44a6c1f"}
     :fileType        "file"
     :created         "2019-11-26T15:41:06.508Z"
     :checksums       [{:checksum "591d9cec" :type "crc32c"}
                       {:checksum "24f38b33c6eac4dd3569e0c4547ced88" :type "md5"}]
     :size            3073329
     :fileId          "271cd32c-2e86-4f46-9eb1-f3ddb44a6c1f"}))

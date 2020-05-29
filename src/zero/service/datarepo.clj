(ns zero.service.datarepo
  "Do stuff in the data repo"
  (:require [clojure.data.json :as json]
            [clj-http.client :as http]
            [zero.environments :as env]
            [zero.once :as once]
            [zero.util :as util])
  (:import [org.apache.http HttpException]))

(defn dr-url
  "URL for the Data Repo in ENVIRONMENT."
  [environment]
  (get-in env/stuff [environment :data-repo :url]))

(defn api
  "API URL for Data Repo API in ENVIRONMENT."
  [environment]
  (str (dr-url environment) "/api/repository/v1"))

(defn collect-specific-header-for
  "Request user log in to gcloud account with specific abilities"
  [description]
  (println (format (str "Google is about to request authorization.\n"
                        "Please sign in with %s.")
                   description))
  (util/shell-io! "gcloud" "auth" "login")
  (util/bearer-token-header-for (once/user-credentials)))

(defonce data-repo-headers
  (delay (collect-specific-header-for
           "an account that can make requests to the Data Repo")))

(defonce gcs-headers
  (delay (collect-specific-header-for
           "an account that can make requests to your gcs bucket")))

(defn thing-ingest
  "Request some ingest into the Data Repository. Returns job id for polling."
  [environment dataset-id thing body]
  (let [url     (format "%s/datasets/%s/%s" (api environment) dataset-id thing)
        request {:method  :post
                 ;; :debug true :debug-body true
                 ;; :throw-exceptions false
                 :url     url
                 :body    body
                 :content-type :application/json
                 :headers @data-repo-headers}]
    (-> (http/request request)
        :body
        (json/read-str :key-fn keyword)
        :id)))

(defn file-ingest
  "Request file ingest in the Data Repository. Returns job id for polling."
  [environment dataset-id profile-id src vdest]
  (let [description "something derived from file name + extension?"
        body (json/write-str {:description description
                              :profileId   profile-id
                              :source_path src
                              ;;will receive error when polling job if not unique
                              :target_path vdest
                              :mime_type   "text/plain"})]
    (thing-ingest environment dataset-id "files" body)))

(defn tabular-ingest
  "Request tabular ingest in Data Repository. Returns job id for polling."
  [environment dataset-id path table]
  (let [body (json/write-str {:format                "json"
                              :ignore_unknown_values true
                              :load_tag              "string"
                              :max_bad_records       0
                              :path                  path
                              :strategy              "upsert"
                              :table                 table})]
    (thing-ingest environment dataset-id "ingest" body)))

(defn get-job-result
  "Get result of a successful job; returns json model specific to job type"
  [environment job-id]
  (let [url (format "%s/jobs/%s/result" (api environment) job-id)
        request {:method :get
                 ;; :debug true :debug-body true
                 :url url
                 :headers @data-repo-headers
                 :throw-exceptions false}
        response (http/request request)
        body (-> response
                 :body
                 (json/read-str :key-fn keyword))]
    (if (#{200} (:status response))
      body
      (throw (HttpException. (json/write-str body))))))

(defn poll-job
  "Poll job for completion, retrieve result if and when it is ready"
  [environment job-id]
  (let [url (format "%s/jobs/%s" (api environment) job-id)
        request {:method :get
                 ;; :debug true :debug-body true
                 :url url
                 :headers @data-repo-headers}
        status (-> (http/request request)
                   :body
                   (json/read-str :key-fn keyword)
                   :job_status)]
    (if (#{"running"} status)
      (do (Thread/sleep 10000)
          (poll-job environment job-id))
      ;; On job failure, this will return 400 and an error message.
      (get-job-result environment job-id))))

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

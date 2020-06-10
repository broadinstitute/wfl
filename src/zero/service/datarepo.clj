(ns zero.service.datarepo
  "Do stuff in the data repo"
  (:require [clojure.data.json :as json]
            [clojure.java.io :as io]
            [clj-http.client :as http]
            [zero.environments :as env]
            [zero.once :as once]
            [zero.util :as util]
            [zero.zero :as zero])
  (:import [org.apache.http HttpException]))

(defn dr-url
  "URL for the Data Repo in ENVIRONMENT."
  [environment]
  (get-in env/stuff [environment :data-repo :url]))

(defn api
  "API URL for Data Repo API in ENVIRONMENT."
  [environment]
  (str (dr-url environment) "/api/repository/v1"))

(defn get-data-repo-header
  "An Authorization header for the Data Repo."
  []
  (let [environment (util/getenv "ZERO_DEPLOY_ENVIRONMENT" "debug")
        env (zero/throw-or-environment-keyword! environment)
        iam "https://iamcredentials.googleapis.com/v1/projects/"
        path (get-in env/stuff [env :server :service-account])
        email (-> path io/reader (json/read :key-fn keyword) :client_email)
        url (str iam "-/serviceAccounts/" email ":generateAccessToken")
        scope ["https://www.googleapis.com/auth/userinfo.email"
               "https://www.googleapis.com/auth/userinfo.profile"]
        token (-> {:method :post        ; :debug true :debug-body true
                   :url     url
                   :headers (once/get-auth-header)
                   :body    (json/write-str {:scope scope})}
                http/request :body
                (json/read-str :key-fn keyword)
                :accessToken)]
    {"Authorization" (str "Bearer" \space token)}))

(defn thing-ingest
  "Ingest THING to DATASET-ID according to BODY in ENVIRONMENT."
  [environment dataset-id thing body]
  (let [url (format "%s/datasets/%s/%s" (api environment) dataset-id thing)]
    (-> {:method       :post            ; :debug true :debug-body true
         :url          url              ; :throw-exceptions false
         :content-type :application/json
         :headers      (get-data-repo-header)
         :body         body}
      http/request :body
      (json/read-str :key-fn keyword)
      :id)))

(defn file-ingest
  "Ingest SRC file as VDEST in ENVIRONMENT using DATASET-ID and PROFILE-ID."
  [environment dataset-id profile-id src vdest]
  (->> {:description "derived from file name + extension?"
        :profileId   profile-id
        :source_path src
        :target_path vdest
        :mime_type   "text/plain"}
    json/write-str
    (thing-ingest environment dataset-id "files")))

(defn tabular-ingest
  "Ingest TABLE at PATH to DATASET-ID in ENVIRONMENT and return the job ID."
  [environment dataset-id path table]
  (->> {:format                "json"
        :ignore_unknown_values true
        :load_tag              "string"
        :max_bad_records       0
        :path                  path
        :table                 table}
    json/write-str
    (thing-ingest environment dataset-id "ingest")))

(defn get-job-result
  "Get result for JOB-ID in ENVIRONMENT."
  [environment job-id]
  (let [{:keys [body status]}
        (http/request
          {:method :get                 ; :debug true :debug-body true
           :url (format "%s/jobs/%s/result" (api environment) job-id)
           :headers (get-data-repo-header)
           :throw-exceptions false})]
    (if (== 200 status)
      (json/read-str body :key-fn keyword)
      (throw (HttpException. body)))))

(defn poll-job
  "Return result for JOB-ID in ENVIRONMENT when it stops running."
  [environment job-id]
  (letfn [(running? []
            (-> {:method :get ; :debug true :debug-body true
                 :url (format "%s/jobs/%s" (api environment) job-id)
                 :headers (get-data-repo-header)}
              http/request :body
              (json/read-str :key-fn keyword)
              :job_status
              #{"running"}))]
    (when (running?)
      (Thread/sleep 10000)
      (poll-job environment job-id))
    (get-job-result environment job-id)))

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

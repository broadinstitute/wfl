(ns zero.testtools
  (:require [clj-http.client :as client]
            [zero.once :as once]
            [zero.util :as util]
            [clojure.data.json :as json]))

(def server
  "https://workflow-launcher.gotc-dev.broadinstitute.org"
  "http://localhost:8080")

(defn parse-json-string
  "Parse the json string STR into a keyword-string map"
  [str]
  (json/read-str str :key-fn keyword))

(defn get-workload-status
  "Query v1 api for the status of the workload with UUID"
  [uuid]
  (let [auth-header (once/get-auth-header!)
        response    (client/get (str server "/api/v1/workload")
                      {:headers      auth-header
                       :query-params {:uuid uuid}})]
    (first (parse-json-string (:body response)))))

(defn get-workloads
  "Query v1 api for all workloads"
  []
  (let [auth-header (once/get-auth-header!)
        response    (client/get (str server "/api/v1/workload")
                      {:headers auth-header})]
    (parse-json-string (:body response))))

(def get-pending-workloads
  "Query the v1 api for the workloads that has not been started"
  (comp (partial remove :started) get-workloads))

(def first-pending-workload
  "Query the v1 api for first workload that has not been started"
  (comp first get-pending-workloads))

(defn first-pending-workload-or
  "Query the v1 api for the first pending workload or evaluate the ALTERNATIVE
  if no pending workload exists"
  [alternative]
  (let [workload (first-pending-workload)]
    (if workload workload (alternative))))

(defn create-workload
  "Create workload defined by WORKLOAD"
  [workload]
  (let [auth-header (once/get-auth-header!)
        payload     (json/write-str workload)
        response    (client/post (str server "/api/v1/create")
                      {:headers      auth-header
                       :content-type :json
                       :accept       :json
                       :body         payload})]
    (parse-json-string (:body response))))

(defn start-workload
  "Start processing WORKLOAD. WORKLOAD must be known to the server."
  [workload]
  (let [auth-header (once/get-auth-header!)
        payload     (json/write-str [workload])
        response    (client/post (str server "/api/v1/start")
                      {:headers      auth-header
                       :content-type :json
                       :accept       :json
                       :body         payload})]
    (parse-json-string (:body response))))

(defn start-wgs-workflow
  "Submit the WGS Reprocessing WORKFLOW"
  [workflow]
  (let [auth-header (once/get-auth-header!)
        payload     (json/write-str workflow :escape-slash false)
        response    (client/post (str server "/api/v1/wgs")
                      {:headers      auth-header
                       :content-type :json
                       :accept       :json
                       :body         payload})]
    (parse-json-string (:body response))))

(defn exec-workload
  "Create and start workload defined by WORKLOAD"
  [workload]
  (let [auth-header (once/get-auth-header!)
        payload     (json/write-str workload)
        response    (client/post (str server "/api/v1/exec")
                      {:headers      auth-header
                       :content-type :json
                       :accept       :json
                       :body         payload})]
    (parse-json-string (:body response))))

(def git-branch (delay (util/shell! "git" "branch" "--show-current")))
(def git-email (delay (util/shell! "git" "config" "user.email")))

(def wgs-workload
  "A whole genome sequencing workload used for testing."
  (let [path "/single_sample/plumbing/truth"]
    {:creator  @git-email
     :cromwell "https://cromwell.gotc-dev.broadinstitute.org"
     :input    (str "gs://broad-gotc-test-storage" path)
     :output   (str "gs://broad-gotc-dev-zero-test/wgs-test-output" path)
     :pipeline "ExternalWholeGenomeReprocessing"
     :project  (format "(Test) %s" @git-branch)
     :items    [{:unmapped_bam_suffix  ".unmapped.bam",
                 :sample_name          "NA12878 PLUMBING",
                 :base_file_name       "NA12878_PLUMBING",
                 :final_gvcf_base_name "NA12878_PLUMBING",
                 :input_cram           "develop/20k/NA12878_PLUMBING.cram"}]}))

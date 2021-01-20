(ns wfl.service.clio
  "Talk to Clio for some reason ..."
  (:require [clojure.java.io :as io]
            [clojure.data.json :as json]
            [clojure.pprint :refer [pprint]]
            [clojure.string :as str]
            [clj-http.client :as http]
            [wfl.environments :as env]
            [wfl.once :as once]
            [wfl.util :as util]
            [wfl.wfl :as wfl])
  (:import [com.google.auth.oauth2 GoogleCredentials]))

(def deploy-environment
  "Where is WFL deployed?"
  (-> "WFL_DEPLOY_ENVIRONMENT"
      (util/getenv "debug")
      wfl/error-or-environment-keyword env/stuff))

(def deploy-environment (:gotc-dev env/stuff))

(defn get-authorization-header
  "An Authorization header for talking to Clio where deployed."
  []
  (with-open [in (-> deploy-environment
                     :google_account_vault_path util/vault-secrets
                     (json/write-str :escape-slash false)
                     .getBytes io/input-stream)]
    (-> in GoogleCredentials/fromStream
        (.createScoped ["https://www.googleapis.com/auth/userinfo.email"
                        "https://www.googleapis.com/auth/userinfo.profile"])
        .refreshAccessToken .getTokenValue
        once/authorization-header-with-bearer-token)))

(defn post
  "Post THING to Clio server with metadata MD."
  [thing md]
  (-> {:url (str/join "/" [(:clio deploy-environment) "api" "v1" thing])
       :method :post ;; :debug true :debug-body true
       :headers (merge {"Content-Type" "application/json"}
                       (get-authorization-header))
       :body (json/json-str md)}
      http/request :body
      (json/read-str :key-fn keyword)))

(defn query-bam
  "Return BAM entries with metadata MD."
  [md]
  (post (str/join "/" ["bam" "query"]) md))

(defn query-cram
  "Return CRAM entries with metadata MD."
  [md]
  (post (str/join "/" ["cram" "query"]) md))

(defn add-bam
  "Add a BAM entry with metadata MD."
  [md]
  (post (str/join "/" ["bam" "add"]) md))

(comment
  "gs://broad-gotc-prod-storage/pipeline/{PROJECT}/{SAMPLE_ALIAS}/v{VERSION}"
  (with-open [out (io/writer (io/file "bam.edn"))]
    (binding [*out* out]
      (pprint (query-bam {}))))
  (def sg
    (filter :insert_size_metrics_path (query-bam {})))
  {:bai_path
   "gs://broad-gotc-test-clio/bam/project/sample/v3/sample.bai"
   :bam_md5 "1065398b26d944fc838a39ca78bd96e9"
   :bam_path
   "gs://broad-gotc-test-clio/bam/project/sample/v3/sample.bam"
   :billing_project "broad-genomics-data"
   :data_type "WGS"
   :document_status "Normal"
   :location "GCP"
   :project "project"
   :sample_alias "sample"
   :version 3
   :workspace_name "TestWorkspace"}
  )

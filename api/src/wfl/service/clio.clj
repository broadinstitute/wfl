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

;; See ApiConstants.scala, ClioWebClientSpec.scala, and BamKey.scala and
;; CramKey.scala in Clio.
;;
;; "https://clio.gotc-dev.broadinstitute.org/api/v1/cram/metadata/GCP/G96830/WGS/NA12878/1"
;;
;; Return a Firebase Push ID string like this: "-MRu7X3zEzoGeFAVSF-J"
;;
(defn add
  [thing md]
  (let [keys [:location :project :data_type :sample_alias :version]
        need (keep (fn [k] (when-not (k md) k)) keys)]
    (when-not (empty need)
      (throw (ex-info "Need these metadata keys:" need)))
    (post (str/join "/" (into [thing "metadata"] ((apply juxt keys) md)))
          (apply dissoc md keys))))

(defn add-bam
  "Add a BAM entry with metadata MD."
  [md]
  (add "bam" md))

(defn add-cram
  "Add a CRAM entry with metadata MD."
  [md]
  (add "cram" md))

(defn query-bam
  "Return BAM entries with metadata MD."
  [md]
  (post (str/join "/" ["bam" "query"]) md))

(defn query-cram
  "Return CRAM entries with metadata MD."
  [md]
  (post (str/join "/" ["cram" "query"]) md))

(comment
  "gs://broad-gotc-prod-storage/pipeline/{PROJECT}/{SAMPLE_ALIAS}/v{VERSION}"
  (let [NA12878 (str/join "/" ["gs://broad-gotc-prod-storage/pipeline"
                               "G96830/NA12878/v454/NA12878"])]
    {:crai_path                  (str NA12878 ".cram.crai")
     :cram_md5                   "0cfd2e0890f45e5f836b7a82edb3776b"
     :cram_path                  (str NA12878 ".cram")
     :cram_size                  19512619343
     :data_type                  "WGS"
     :document_status            "Normal"
     :insert_size_histogram_path (str NA12878 ".insert_size_histogram.pdf")
     :insert_size_metrics_path   (str NA12878 ".insert_size_metrics")
     :location                   "GCP"
     :notes                      "Blame tbl for somatic genomes testing."
     :project                    "G96830"
     :regulatory_designation     "RESEARCH_ONLY"
     :sample_alias               "NA12878"
     :version                    454})

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

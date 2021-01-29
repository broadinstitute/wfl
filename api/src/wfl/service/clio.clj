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
       :method :post                    ; :debug true :debug-body true
       :headers (merge {"Content-Type" "application/json"}
                       (get-authorization-header))
       :body (json/write-str md :escape-slash false)}
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


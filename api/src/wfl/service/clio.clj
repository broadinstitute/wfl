(ns wfl.service.clio
  "Manage Clio's BAM and CRAM indexes."
  (:require [clojure.data.json :as json]
            [clojure.string :as str]
            [clj-http.client :as http]
            [wfl.auth :as auth]))

(defn api
  "The Clio API URL for server at `clio`."
  [clio]
  (str/join "/" [clio "api" "v1"]))

(defn post
  "Post `thing` to `clio` server with metadata `md`."
  [clio thing md]
  (-> {:url     (str/join "/" [(api clio) thing])
       :method  :post                   ; :debug true :debug-body true
       :headers (merge {"Content-Type" "application/json"}
                       (auth/get-service-account-header))
       :body    (json/write-str md :escape-slash false)}
      http/request :body
      (json/read-str :key-fn keyword)))

(def add-keys
  "The keys Clio metadata needs to add a BAM or CRAM record."
  [:location :project :data_type :sample_alias :version])

;; See ApiConstants.scala, ClioWebClientSpec.scala, and BamKey.scala and
;; CramKey.scala in Clio.
;;
;; "https://clio.gotc-dev.broadinstitute.org/api/v1/cram/metadata/GCP/G96830/WGS/NA12878/1"
;;
;; Return a Firebase Push ID string like this: "-MRu7X3zEzoGeFAVSF-J"
;;
(defn ^:private add
  "Add `thing` with metadata `md` to server at `clio`."
  [clio thing md]
  (let [need (keep (fn [k] (when-not (k md) k)) add-keys)]
    (when-not (empty need)
      (throw (ex-info "Need these metadata keys:" {:need need})))
    (post clio
          (str/join "/" (into [thing "metadata"] ((apply juxt add-keys) md)))
          (apply dissoc md add-keys))))

(defn add-bam
  "Add a BAM entry with metadata `md` to `clio`."
  [clio md]
  (add clio "bam" md))

(defn add-cram
  "Add a CRAM entry with metadata `md` to `clio`."
  [clio md]
  (add clio "cram" md))

(defn query-bam
  "Return BAM entries with metadata `md` to `clio`."
  [clio md]
  (post clio (str/join "/" ["bam" "query"]) md))

(defn query-cram
  "Return CRAM entries with metadata `md` to `clio`."
  [clio md]
  (post clio (str/join "/" ["cram" "query"]) md))

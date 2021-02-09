(ns wfl.service.clio
  "Talk to Clio for some reason ..."
  (:require [clojure.data.json :as json]
            [clojure.pprint :refer [pprint]]
            [clojure.string :as str]
            [clj-http.client :as http]
            [wfl.once :as once]))

(def url
  "The Clio API URL."
  "https://clio.gotc-dev.broadinstitute.org/api/v1")

(defn get-authorization-header
  "An Authorization header for talking to Clio where deployed."
  []
  (once/authorization-header-with-bearer-token (once/service-account-token)))

(defn post
  "Post THING to Clio server with metadata MD."
  [thing md]
  (-> {:url     (str/join "/" [url thing])
       :method  :post                   ; :debug true :debug-body true
       :headers (merge {"Content-Type" "application/json"}
                       (get-authorization-header))
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
  [thing md]
  (let [need (keep (fn [k] (when-not (k md) k)) add-keys)]
    (when-not (empty need)
      (throw (ex-info "Need these metadata keys:" need)))
    (post (str/join "/" (into [thing "metadata"] ((apply juxt add-keys) md)))
          (apply dissoc md add-keys))))

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

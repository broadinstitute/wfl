(ns wfl.service.google.storage
  "Wrappers for Google Cloud Storage REST APIs.
  See https://cloud.google.com/storage/docs/json_api/v1"
  (:require [clj-http.client                :as http]
            [clj-http.util                  :as http-util]
            [clojure.data.json              :as json]
            [clojure.java.io                :as io]
            [clojure.pprint                 :refer [pprint]]
            [clojure.set                    :as set]
            [clojure.spec.alpha             :as s]
            [clojure.string                 :as str]
            [clojure.tools.logging.readable :as logr]
            [wfl.once :as once]
            [wfl.util :as util])
  (:import [org.apache.tika Tika]))

(def api-url
  "The Google Cloud API URL."
  "https://www.googleapis.com/")

(def storage-url
  "The Google Cloud URL for storage operations."
  (str api-url "storage/v1/"))

(def bucket-url
  "The Google Cloud Storage URL for bucket operations."
  (str storage-url "b/"))

(def upload-url
  "The Google Cloud Storage URL for upload operations."
  (str api-url "upload/storage/v1/b/"))

(defn bucket-object-url
  "The API URL referring to OBJECT in BUCKET."
  [bucket object]
  (str bucket-url bucket "/o/" (http-util/url-encode object)))

(defn parse-gs-url
  "Return BUCKET and OBJECT from a gs://bucket/object URL."
  [url]
  (let [[gs-colon nada bucket object] (str/split url #"/" 4)]
    (when-not
     (and (every? seq [gs-colon bucket])
          (= "gs:" gs-colon)
          (= "" nada))
      (throw (IllegalArgumentException. (format "Bad GCS URL: '%s'" url))))
    [bucket (or object "")]))

(defn gs-url
  "Format BUCKET and OBJECT name into a gs://bucket/object URL."
  [bucket object]
  (when-not (and (string?        bucket)
                 (seq            bucket)
                 (not-any? #{\/} bucket))
    (let [fmt "The bucket (%s) must be a non-empty string."
          msg (format fmt bucket)]
      (throw (IllegalArgumentException. msg))))
  (str "gs://" bucket "/" object))

(defn gs-object-url
  "Format OBJECT into a gs://bucket/name URL."
  [{:keys [bucket name] :as object}]
  (when-not (and (map? object) bucket name)
    (throw (IllegalArgumentException.
            (str/join \newline ["Object must have :bucket and :name keys:"
                                (with-out-str (pprint object))]))))
  (gs-url bucket name))

(defn iam
  "Return IamPolicy response for URL."
  [url]
  (let [[bucket _] (parse-gs-url url)]
    (-> {:method       :get             ; :debug true :debug-body true
         :url          (str bucket-url bucket "/iam")
         ;; :query-params {:project project :prefix prefix}
         :content-type :application/json
         :headers      (once/get-auth-header)}
        http/request :body
        (json/read-str :key-fn keyword))))

(defn list-buckets
  "The buckets in PROJECT named with optional PREFIX."
  ([project prefix]
   (-> {:method       :get              ; :debug true :debug-body true
        :url          bucket-url
        :query-params {:project project :prefix prefix}
        :content-type :application/json
        :headers      (once/get-auth-header)}
       http/request :body
       (json/read-str :key-fn keyword)
       :items
       (or [])))
  ([project]
   (list-buckets project "")))

(defn list-objects
  "The objects in BUCKET with PREFIX in a lazy sequence."
  ([bucket prefix]
   (letfn [(each [pageToken]
             (let [{:keys [items nextPageToken]}
                   (-> {:method       :get
                        :url          (str bucket-url bucket "/o")
                        :content-type :application/json
                        :headers      (once/get-auth-header)
                        :query-params {:prefix prefix
                                       :maxResults 999
                                       :pageToken pageToken}}
                       http/request
                       :body
                       (json/read-str :key-fn keyword))]
               (lazy-cat items (when nextPageToken (each nextPageToken)))))]
     (each "")))
  ([url]
   (apply list-objects (parse-gs-url url))))

(def _-? (set "_-"))
(def digit? (set "0123456789"))
(def lowercase? (set "abcdefghijklmnopqrstuvwxyz"))

(s/def ::bucket-name
  (s/and string?
         (partial every? (set/union _-? digit? lowercase?))
         (complement (comp _-? first))
         (complement (comp _-? last))
         (comp (partial > 64) count)
         (comp (partial <  2) count)))

(defn valid-bucket-name-or-throw!
  "Throw unless BUCKET is a valid GCS bucket name."
  [bucket]
  (when-not (s/valid? ::bucket-name bucket)
    (throw
     (IllegalArgumentException.
      (format "Bad GCS bucket name: %s\nSee %s\n%s" bucket
              "https://cloud.google.com/storage/docs/naming#requirements"
              (s/explain-str ::bucket-name bucket))))))

(defn upload-file
  "Upload FILE to BUCKET with name OBJECT."
  ([file bucket object]
   (let [body (io/file file)]
     (-> {:method       :post           ; :debug true :debug-body true
          :url          (str upload-url bucket "/o")
          :query-params {:uploadType "media"
                         :name       object}
          :content-type (.detect (new Tika) body)
          :headers      (once/get-auth-header)
          :body         body}
         http/request :body
         (json/read-str :key-fn keyword))))
  ([file url]
   (apply upload-file file (parse-gs-url url))))

(defn download-file
  "Download URL or OBJECT from BUCKET to FILE."
  ([file bucket object]
   (with-open [out (io/output-stream file)]
     (-> {:method       :get            ; :debug true :debug-body true
          :url          (bucket-object-url bucket object)
          :query-params {:alt "media"}
          :headers      (once/get-auth-header)
          :as           :stream}
         http/request :body
         (io/copy out))))
  ([file url]
   (apply download-file file (parse-gs-url url))))

(defn create-object
  "Create OBJECT in BUCKET"
  ([bucket object]
   (http/request {:method  :post
                  :url     (str upload-url bucket "/o")
                  :query-params {:name object}
                  :headers (once/get-auth-header)}))
  ([url]
   (apply create-object (parse-gs-url url))))

(defn delete-object
  "Delete URL or OBJECT from BUCKET."
  ([bucket object]
   (http/request {:method  :delete      ; :debug true :debug-body true
                  :url     (bucket-object-url bucket object)
                  :headers (once/get-auth-header)}))
  ([url]
   (apply delete-object (parse-gs-url url))))

(defn object-meta
  "Get metadata on URL or OBJECT in BUCKET."
  ([bucket object params]
   (-> {:method       :get              ; :debug true :debug-body true
        :url          (str (bucket-object-url bucket object) params)
        :headers      (once/get-auth-header)}
       http/request
       :body
       (json/read-str :key-fn keyword)))
  ([url]
   (let [[bucket object] (parse-gs-url url)]
     (object-meta bucket object ""))))

(defn patch-object!
  "Patch the METADATA on URL or OBJECT in BUCKET."
  ([metadata bucket object]
   (-> {:method       :patch            ; :debug true :debug-body true
        :url          (bucket-object-url bucket object)
        :content-type :application/json
        :headers      (once/get-auth-header)
        :body         (json/write-str metadata :escape-slash false)}
       http/request :body
       (json/read-str :key-fn keyword)))
  ([metadata url]
   (apply patch-object! metadata (parse-gs-url url))))

(defn copy-object
  "Copy SOBJECT in SBUCKET to DOBJECT in DBUCKET."
  ([sbucket sobject dbucket dobject]
   (let [surl (bucket-object-url sbucket sobject)
         durl (bucket-object-url dbucket dobject)
         destination (str/replace-first durl storage-url "")]
     (-> {:method  :post                ; :debug true :debug-body true
          :url     (str surl "/rewriteTo/" destination)
          :headers (once/get-auth-header)}
         http/request
         :body
         (json/read-str :key-fn keyword))))
  ([source-url destination-url]
   (let [[sbucket sobject] (parse-gs-url source-url)
         [dbucket dobject] (parse-gs-url destination-url)]
     (copy-object sbucket sobject dbucket dobject))))

(defn add-object-reader
  "Add USER as a reader on OBJECT in BUCKET in gcs"
  ([email bucket object]
   (let [acl       (partial cons {:entity (str "user-" email)
                                  :role   "READER"
                                  :email  email})
         meta      (object-meta bucket object "?projection=full")
         acl-entry (update meta :acl acl)]
     (patch-object! acl-entry bucket object)))
  ([email url]
   (apply add-object-reader email (parse-gs-url url))))

(defn patch-bucket!
  "Patch BUCKET in PROJECT with METADATA."
  [project bucket metadata]
  (valid-bucket-name-or-throw! bucket)
  (letfn [(mine? [b] (when (= bucket (:id b)) b))]
    (let [buckets (keep mine? (list-buckets project bucket))]
      (when-not (== 1 (count buckets))
        (throw (IllegalArgumentException.
                (format "Found %s buckets named %s in project %s."
                        (count buckets) bucket project))))
      (-> {:method       :patch         ; :debug true :debug-body true
           :url          (:selfLink (first buckets))
           :content-type :application/json
           :headers      (once/get-auth-header)
           :body         (json/write-str metadata :escape-slash false)}
          http/request :body (json/read-str :key-fn keyword)))))

(defn userinfo
  "Query Google Cloud services for who made the http REQUEST"
  [request]
  (if-let [auth-token (or (get-in request [:headers "Authorization"])
                          (get-in request [:headers "authorization"]))]
    (let [response (http/get (str api-url "oauth2/v3/userinfo")
                             {:headers {"Authorization" auth-token}})]
      (json/read-str (:body response) :key-fn keyword))
    (do
      (logr/error "No auth header in request")
      (throw
       (ex-info "No auth header in request"
                {:type :clj-http.client/unexceptional-status})))))

;; Google Cloud Storage Bucket Notification Configuration
;; See https://cloud.google.com/storage/docs/json_api/v1/notifications

(defn create-notification-configuration [bucket topic]
  (let [payload {:payload_format "JSON_API_V1"
                 :event_types    ["OBJECT_FINALIZE"]
                 :topic          topic}]
    (-> (str bucket-url bucket "/notificationConfigs")
        (http/post
         {:headers      (once/get-auth-header)
          :content-type :json
          :body         (json/write-str payload :escape-slash false)})
        :body
        util/parse-json)))

(defn delete-notification-configuration [bucket {:keys [id]}]
  (http/delete
   (str bucket-url bucket "/notificationConfigs/" id)
   {:headers (once/get-auth-header)}))

(defn list-notification-configurations [bucket]
  (-> (str bucket-url bucket "/notificationConfigs")
      (http/get {:headers (once/get-auth-header)})
      :body
      util/parse-json
      :items))

(defn get-cloud-storage-service-account [project]
  (-> (str storage-url (str/join "/" ["projects" project "serviceAccount"]))
      (http/get {:headers (once/get-auth-header)})
      :body
      util/parse-json))

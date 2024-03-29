(ns wfl.service.google.storage
  "Use the Google Cloud Storage APIs."
  (:require [clojure.data.json  :as json]
            [clojure.java.io    :as io]
            [clojure.pprint     :refer [pprint]]
            [clojure.spec.alpha :as s]
            [clojure.string     :as str]
            [clj-http.client    :as http]
            [clj-http.util      :as http-util]
            [wfl.auth           :as auth]
            [wfl.log            :as log]
            [wfl.util           :as util])
  (:import [org.apache.tika Tika]))

;; See https://cloud.google.com/storage/docs/json_api/v1

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

(defn get-iam-policy
  "Return IamPolicy response for `bucket`."
  [bucket]
  (-> (str bucket-url bucket "/iam")
      (http/get {:headers (auth/get-auth-header)})
      util/response-body-json))

(defn set-iam-policy
  "Set IamPolicy `bucket` to `policy`."
  [bucket policy]
  (-> (str bucket-url bucket "/iam")
      (http/put {:headers      (auth/get-auth-header)
                 :content-type :application/json
                 :body         (json/write-str policy :escape-slash false)})
      util/response-body-json))

(defn list-buckets
  "The buckets in PROJECT named with optional PREFIX."
  ([project prefix]
   (-> {:method       :get              ; :debug true :debug-body true
        :url          bucket-url
        :query-params {:project project :prefix prefix}
        :content-type :application/json
        :headers      (auth/get-auth-header)}
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
                        :headers      (auth/get-auth-header)
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

(def ^:private _-? (set "_-"))
(def ^:private bucket-allowed? (into _-? (concat util/digit? util/lowercase?)))

(s/def ::bucket-name
  (s/and string?
         (partial every? bucket-allowed?)
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

(defn upload-content
  "Upload CONTENT to BUCKET with name OBJECT."
  ([content bucket object]
   (-> (str upload-url bucket "/o")
       (http/post {:query-params {:uploadType "media" :name object}
                   :content-type (.detect (new Tika) content)
                   :headers      (auth/get-auth-header)
                   :body         content})
       util/response-body-json))
  ([content url]
   (apply upload-content content (parse-gs-url url))))

(defn upload-file
  "Upload FILE to BUCKET with name OBJECT."
  ([file bucket object]
   (upload-content (io/file file) bucket object))
  ([file url]
   (apply upload-file file (parse-gs-url url))))

(defn download-file
  "Download URL or OBJECT from BUCKET to FILE."
  ([file bucket object]
   (with-open [out (io/output-stream file)]
     (-> {:method       :get            ; :debug true :debug-body true
          :url          (bucket-object-url bucket object)
          :query-params {:alt "media"}
          :headers      (auth/get-auth-header)
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
                  :headers (auth/get-auth-header)}))
  ([url]
   (apply create-object (parse-gs-url url))))

(defn delete-object
  "Delete URL or OBJECT from BUCKET."
  ([bucket object]
   (http/request {:method  :delete      ; :debug true :debug-body true
                  :url     (bucket-object-url bucket object)
                  :headers (auth/get-auth-header)}))
  ([url]
   (apply delete-object (parse-gs-url url))))

(defn object-meta
  "Get metadata on URL or OBJECT in BUCKET."
  ([bucket object params]
   (-> {:method       :get              ; :debug true :debug-body true
        :url          (str (bucket-object-url bucket object) params)
        :headers      (auth/get-auth-header)}
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
        :headers      (auth/get-auth-header)
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
          :headers (auth/get-auth-header)}
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

(defn add-storage-object-viewer
  "Give service-account `email` the \"Storage Object Viewer\" role in `bucket`."
  [email bucket]
  (let [new-binding [{:role   "roles/storage.objectViewer"
                      :members [(str "serviceAccount:" email)]}]]
    (-> (get-iam-policy bucket)
        (update :bindings cons new-binding)
        (->> (set-iam-policy bucket)))))

(defn userinfo
  "Query Google Cloud services for who made the http REQUEST"
  [request]
  (if-let [auth-token (or (get-in request [:headers "Authorization"])
                          (get-in request [:headers "authorization"]))]
    (let [response (http/get (str api-url "oauth2/v3/userinfo")
                             {:headers {"Authorization" auth-token}})]
      (json/read-str (:body response) :key-fn keyword))
    (do
      (log/error "No auth header in request")
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
         {:headers      (auth/get-auth-header)
          :content-type :json
          :body         (json/write-str payload :escape-slash false)})
        :body
        util/parse-json)))

(defn delete-notification-configuration [bucket {:keys [id]}]
  (http/delete
   (str bucket-url bucket "/notificationConfigs/" id)
   {:headers (auth/get-auth-header)}))

(defn list-notification-configurations [bucket]
  (-> (str bucket-url bucket "/notificationConfigs")
      (http/get {:headers (auth/get-auth-header)})
      :body
      util/parse-json
      :items))

(defn get-cloud-storage-service-account [project]
  (-> (str storage-url (str/join "/" ["projects" project "serviceAccount"]))
      (http/get {:headers (auth/get-auth-header)})
      :body
      util/parse-json))

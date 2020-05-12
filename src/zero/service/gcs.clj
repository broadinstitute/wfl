(ns zero.service.gcs
  "Talk to Google Cloud Storage for some reason..."
  (:require [clojure.data.json  :as json]
            [clojure.java.io    :as io]
            [clojure.set        :as set]
            [clojure.spec.alpha :as s]
            [clojure.string     :as str]
            [clj-http.client    :as http]
            [clj-http.util      :as http-util]
            [zero.once          :as once])
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
  "Format BUCKET and OBJECT into a gs://bucket/object URL."
  [bucket object]
  (when-not (and (string?        bucket)
                 (seq            bucket)
                 (not-any? #{\/} bucket))
    (let [fmt "The bucket (%s) must be a non-empty string."
          msg (format fmt bucket)]
      (throw (IllegalArgumentException. msg))))
  (str "gs://" bucket "/" object))

(defn iam
  "Return IamPolicy response for URL."
  [url]
  (let [[bucket _] (parse-gs-url url)]
    (-> {:method       :get ;; :debug true :debug-body true
         :url          (str bucket-url bucket "/iam")
         ;; :query-params {:project project :prefix prefix}
         :content-type :application/json
         :headers      (once/get-auth-header!)}
        http/request
        :body
        (json/read-str :key-fn keyword))))

(defn list-buckets
  "The buckets in PROJECT named with optional PREFIX."
  ([project prefix]
   (-> {:method       :get ;; :debug true :debug-body true
        :url          bucket-url
        :query-params {:project project :prefix prefix}
        :content-type :application/json
        :headers      (once/get-auth-header!)}
       http/request
       :body
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
                        :headers      (once/get-auth-header!)
                        :query-params {:prefix prefix
                                       :maxResults 999
                                       :pageToken pageToken}}
                       http/request
                       :body
                       (json/read-str :key-fn keyword))]
               (lazy-cat items (when nextPageToken (each nextPageToken)))))]
     (each "")))
  ([bucket]
   (list-objects bucket "")))

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

(defn make-bucket
  "Make a storageClass CLASS bucket in PROJECT named NAME at LOCATION."
  ([project location class name headers]
   (when-not (s/valid? ::bucket-name name)
     (throw
       (IllegalArgumentException.
         (format "Bad GCS bucket name: %s\nSee %s\n%s"
                 name
                 "https://cloud.google.com/storage/docs/naming#requirements"
                 (s/explain-str ::bucket-name name)))))
   (-> {:method       :post ;; :debug true :debug-body true
        :url          bucket-url
        :query-params {:project project}
        :content-type :application/json
        :headers      headers
        :form-params  {:name         name
                       :location     location
                       :storageClass class}}
       http/request
       :body
       (json/read-str :key-fn keyword)))
  ([project location class name]
   (make-bucket project location class name (once/get-auth-header!))))

(defn delete-bucket
  "Throw or delete the bucket in PROJECT named NAME."
  ([name headers]
   (letfn [(deleted-this-time? [response]
             (case (:status response)
               204 true
               404 false
               (-> 'delete-bucket
                   (list name)
                   pr-str
                   (ex-info response)
                   throw)))]
     (-> {:method            :delete ;; :debug true :debug-body true
          :url               (str bucket-url name)
          :headers           headers
          :throw-exceptions? false}
         http/request
         deleted-this-time?)))
  ([name]
   (delete-bucket name (once/get-auth-header!))))

(defn upload-file
  "Upload FILE to BUCKET with name OBJECT."
  ([file bucket object headers]
   (let [body (io/file file)]
     (-> {:method       :post ;; :debug true :debug-body true
          :url          (str upload-url bucket "/o")
          :query-params {:uploadType "media"
                         :name       object}
          :content-type (.detect (new Tika) body)
          :headers      headers
          :body         body}
         http/request
         :body
         (json/read-str :key-fn keyword))))
  ([file bucket object]
   (upload-file file bucket object (once/get-auth-header!)))
  ([file url]
   (apply upload-file file (parse-gs-url url))))

(defn download-file
  "Download URL or OBJECT from BUCKET to FILE."
  ([file bucket object]
   (with-open [out (io/output-stream file)]
     (-> {:method       :get ;; :debug true :debug-body true
          :url          (bucket-object-url bucket object)
          :query-params {:alt "media"}
          :headers      (once/get-auth-header!)
          :as           :stream}
         http/request
         :body
         (io/copy out))))
  ([file url]
   (apply download-file file (parse-gs-url url))))

(defn create-object
  "Create OBJECT in BUCKET"
  ([bucket object headers]
   (http/request {:method  :post
                  :url     (str upload-url bucket "/o")
                  :query-params {:name object}
                  :headers headers}))
  ([bucket object]
   (create-object bucket object (once/get-auth-header!)))
  ([url]
   (apply create-object (parse-gs-url url))))

(defn delete-object
  "Delete URL or OBJECT from BUCKET"
  ([bucket object headers]
   (http/request {:method  :delete ;; :debug true :debug-body true
                  :url     (bucket-object-url bucket object)
                  :headers headers}))
  ([bucket object]
   (delete-object bucket object (once/get-auth-header!)))
  ([url]
   (apply delete-object (parse-gs-url url))))

(defn object-meta
  "Get metadata on URL or OBJECT in BUCKET."
  ([bucket object params headers]
   (-> {:method       :get ;; :debug true :debug-body true
        :url          (str (bucket-object-url bucket object) params)
        :headers      headers}
       http/request
       :body
       (json/read-str :key-fn keyword)))
  ([bucket object]
   (object-meta bucket object "" (once/get-auth-header!)))
  ([url]
   (apply object-meta (parse-gs-url url))))

(defn patch-object!
  "Patch the METADATA on URL or OBJECT in BUCKET."
  ([metadata bucket object headers]
   (-> {:method       :patch ;; :debug true :debug-body true
        :url          (bucket-object-url bucket object)
        :content-type :application/json
        :headers      headers
        :body         (json/write-str metadata)}
       http/request
       :body
       (json/read-str :key-fn keyword)))
  ([metadata bucket object]
   (patch-object! metadata bucket object (once/get-auth-header!)))
  ([metadata url]
   (apply patch-object! metadata (parse-gs-url url))))

(defn copy-object
  "Copy SRC-URL to DEST-URL or SOBJ in SBUCKET to DOBJ in DBUCKET."
  ([src-url dest-url]
   (let [destination (str/replace-first dest-url storage-url "")]
     (-> {:method  :post ;; :debug true :debug-body true
          :url     (str src-url "/rewriteTo/" destination)
          :headers (once/get-auth-header!)}
         http/request :body
         (json/read-str :key-fn keyword))))
  ([sbucket sobject dbucket dobject]
   (let [src-url  (bucket-object-url sbucket sobject)
         dest-url (bucket-object-url dbucket dobject)]
     (copy-object src-url dest-url))))

(defn add-object-reader
  "Add USER as a reader on OBJECT in BUCKET in gcs"
  ([email bucket object headers]
   (let [acl-entry {:entity (str "user-" email)
                    :role   "READER"
                    :email  email}
         acl (update (object-meta bucket object "?projection=full" headers)
                     :acl (partial cons acl-entry))]
     (patch-object! acl bucket object headers)))
  ([email bucket object]
   (add-object-reader email bucket object (once/get-auth-header!))))

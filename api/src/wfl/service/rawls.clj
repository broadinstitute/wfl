(ns wfl.service.rawls
  "Analyze data in Terra using the Rawls API.
  Note that Firecloud is built on top of Rawls:
  Rawls may expose functionality that hasn't yet been promoted to Firecloud."
  (:require [clj-http.client :as http]
            [clojure.data.json :as json]
            [clojure.string :as str]
            [wfl.auth :as auth]
            [wfl.environment :as env]
            [wfl.service.datarepo :as datarepo]
            [wfl.util :as util]))

(defn ^:private rawls-url [& parts]
  (let [url (util/de-slashify (env/getenv "WFL_RAWLS_URL"))]
    (str/join "/" (cons url parts))))

(def ^:private workspace-api-url
  (partial rawls-url "api/workspaces"))

(defn ^:private get-workspace-json [& parts]
  (-> (apply workspace-api-url parts)
      (http/get {:headers (auth/get-auth-header)})
      util/response-body-json))

(defn create-snapshot-reference
  "Link SNAPSHOT-ID to WORKSPACE as NAME with DESCRIPTION.
  If NAME unspecified, generate a unique reference name from the snapshot name."
  ([workspace snapshot-id name description]
   (-> (workspace-api-url workspace "snapshots")
       (http/post {:headers      (auth/get-auth-header)
                   :content-type :application/json
                   :body         (json/write-str {:snapshotId  snapshot-id
                                                  :name        name
                                                  :description description}
                                                 :escape-slash false)})
       util/response-body-json))
  ([workspace snapshot-id name]
   (create-snapshot-reference workspace snapshot-id name ""))
  ([workspace snapshot-id]
   (let [name (util/randomize (:name (datarepo/snapshot snapshot-id)))]
     (create-snapshot-reference workspace snapshot-id name))))

(defn get-snapshot-reference
  "Return the snapshot reference in fully-qualified Terra WORKSPACE with REFERENCE-ID."
  [workspace reference-id]
  (get-workspace-json workspace "snapshots" reference-id))

(defn delete-snapshot-reference
  "Delete the snapshot reference in fully-qualified Terra WORKSPACE with REFERENCE-ID."
  [workspace reference-id]
  (-> (workspace-api-url workspace "snapshots" reference-id)
      (http/delete {:headers (auth/get-auth-header)})
      util/response-body-json))

(ns wfl.service.rawls
  "Analyze data in Terra using the Rawls API.
  Note that Firecloud is built on top of Rawls:
  Rawls may expose functionality that hasn't yet been promoted to Firecloud."
  (:require [clj-http.client :as http]
            [clojure.data.json :as json]
            [clojure.string :as str]
            [wfl.auth :as auth]
            [wfl.environment :as env]
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
  "Link a Terra Data Repo snapshot with id SNAPSHOT-ID to a fully-qualified
  Terra WORKSPACE as NAME, optionally described by DESCRIPTION."
  ([workspace snapshot-id name description]
   (-> {:method       :post
        :url          (workspace-api-url workspace "snapshots")
        :headers      (auth/get-auth-header)
        :content-type :application/json
        :body         (json/write-str {:snapshotId snapshot-id
                                       :name name
                                       :description description}
                                      :escape-slash false)}
       http/request
       util/response-body-json
       :referenceId))
  ([workspace snapshot-id name]
   (create-snapshot-reference workspace snapshot-id name "")))

(defn get-snapshot
  "Return the snapshot in fully-qualified Terra WORKSPACE with REFERENCE-ID."
  [workspace reference-id]
  (get-workspace-json workspace "snapshots" reference-id))

(defn delete-snapshot
  "Delete the snapshot in fully-qualified Terra WORKSPACE with REFERENCE-ID."
  [workspace reference-id]
  (-> (workspace-api-url workspace "snapshots" reference-id)
      (http/delete {:headers (auth/get-auth-header)})
      util/response-body-json))

(defn get-workspace
  "Query Rawls for the workspace with the workspace name."
  [workspace]
  {:pre [(some? workspace)]}
  (get-workspace-json workspace))
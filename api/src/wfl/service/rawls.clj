(ns wfl.service.rawls
  "Analyze and manipulate Terra Workspaces using the Rawls API.
   Note that firecloud exposes a subset of Rawls' API."
  (:require [clj-http.client      :as http]
            [clojure.data.json    :as json]
            [clojure.string       :as str]
            [wfl.api.spec         :as spec]
            [wfl.auth             :as auth]
            [wfl.environment      :as env]
            [wfl.service.datarepo :as datarepo]
            [wfl.util             :as util]))

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
  "Link SNAPSHOT-ID to WORKSPACE as NAME with DESCRIPTION."
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
   (create-snapshot-reference workspace snapshot-id name "")))

(defn get-snapshot-reference
  "Return the snapshot reference in fully-qualified Terra WORKSPACE with REFERENCE-ID."
  [workspace reference-id]
  (get-workspace-json workspace "snapshots" reference-id))

(defn delete-snapshot
  "Delete the snapshot in fully-qualified Terra WORKSPACE with REFERENCE-ID."
  [workspace reference-id]
  (-> (workspace-api-url workspace "snapshots" reference-id)
      (http/delete {:headers (auth/get-auth-header)})
      util/response-body-json))

(defn batch-upsert
  "Batch update and insert entities into a `workspace`."
  [workspace [[_name _type _attributes] & _ :as entities]]
  {:pre [(string? workspace) (not-empty entities)]}
  (letfn [(add-scalar [k v]
            [{:op                 "AddUpdateAttribute"
              :attributeName      (name k)
              :addUpdateAttribute v}])
          (add-list [k v]
            (let [list-name   (name k)
                  init        {:op            "CreateAttributeValueList"
                               :attributeName list-name}
                  template    {:op                "AddListMember"
                               :attributeListName list-name}
                  make-member (partial assoc template :newMember)]
              (reduce #(conj %1 (make-member %2)) [init] v)))
          (no-op [] [])
          (on-unhandled-attribute [name value]
            (throw (ex-info "No method to make upsert operation for attribute"
                            {:name name :value value})))
          (to-operations [attributes]
            (flatten
             (for [[k v] (seq attributes)]
               (cond (number? v) (add-scalar k v)
                     (string? v) (add-scalar k v)
                     (map?    v) (add-scalar k v)
                     (coll?   v) (add-list   k v)
                     (nil?    v) (no-op)
                     :else       (on-unhandled-attribute k v)))))
          (make-request [[name type attributes]]
            {:name name :entityType type :operations (to-operations attributes)})]
    (-> (workspace-api-url workspace "entities/batchUpsert")
        (http/post {:headers      (auth/get-auth-header)
                    :content-type :application/json
                    :body         (json/write-str (map make-request entities)
                                                  :escape-slash false)})
        util/response-body-json)))

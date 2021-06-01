(ns wfl.service.rawls
  "Analyze and manipulate Terra Workspaces using the Rawls API.
   Note that firecloud exposes a subset of Rawls' API."
  (:require [clj-http.client      :as http]
            [clojure.data.json    :as json]
            [clojure.string       :as str]
            [wfl.auth             :as auth]
            [wfl.environment      :as env]
            [wfl.util             :as util])
  (:import [clojure.lang ExceptionInfo]))

(defn ^:private rawls-url [& parts]
  (let [url (util/de-slashify (env/getenv "WFL_RAWLS_URL"))]
    (str/join "/" (cons url parts))))

(def ^:private workspace-api-url
  (partial rawls-url "api/workspaces"))

(defn ^:private get-workspace-json [& parts]
  (-> (apply workspace-api-url parts)
      (http/get {:headers (auth/get-auth-header)})
      util/response-body-json))

(def ^:private snapshot-endpoint
  "snapshots")

(defn create-snapshot-reference
  "Link `snapshot-id` to `workspace` as `name` with `description`."
  ([workspace snapshot-id name description]
   (-> (workspace-api-url workspace snapshot-endpoint)
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
  "Return the snapshot reference in `workspace` with `reference-id`."
  [workspace reference-id]
  (get-workspace-json workspace snapshot-endpoint reference-id))

(defn get-snapshot-references
  "Lazily returns the snapshot references in `workspace`
  with `limit` resources per page (default: 100)."
  ([workspace limit]
   (letfn
    [(page [offset]
       (let [{:keys [resources] :as _response}
             (-> (workspace-api-url workspace snapshot-endpoint)
                 (http/get {:headers      (auth/get-auth-header)
                            :query-params {:offset offset
                                           :limit  limit}})
                 (util/response-body-json))
             total    (+ offset (count resources))]
         (lazy-cat resources (when (seq resources) (page total)))))]
     (util/lazy-unchunk (page 0))))
  ([workspace]
   (get-snapshot-references workspace 100)))

(defn ^:private get-reference-for-snapshot-id
  "Return first snapshot reference for `snapshot-id` in `workspace`."
  [workspace snapshot-id]
  (->> (get-snapshot-references workspace)
       (filter #(= (get-in % [:reference :snapshot]) snapshot-id))
       first))

(def ^:private reference-creation-failed-message
  (str/join " " ["Could not create snapshot reference and"
                 "found no snapshot reference matching the"
                 "snapshot id."]))

(defn create-or-get-snapshot-reference
  "Return first snapshot reference for `snapshot-id` in `workspace`,
  creating it as `name` with `description` if it does not yet exist."
  ([workspace snapshot-id name description]
   (try
     (create-snapshot-reference workspace snapshot-id name description)
     (catch ExceptionInfo cause
       (when-not (== 409 (:status (ex-data cause)))
         (throw cause))
       (or (get-reference-for-snapshot-id workspace snapshot-id)
           (throw (ex-info reference-creation-failed-message
                           {:workspace   workspace
                            :snapshot-id snapshot-id
                            :name        name}
                           cause))))))
  ([workspace snapshot-id name]
   (create-or-get-snapshot-reference workspace snapshot-id name "")))

(defn delete-snapshot-reference
  "Delete the snapshot reference in `workspace` with `reference-id`."
  [workspace reference-id]
  (-> (workspace-api-url workspace snapshot-endpoint reference-id)
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

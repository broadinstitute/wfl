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

(defn snapshots
  "Return a lazy sequence of snapshots in WORKSPACE namespace/name."
  [workspace]
  (let [rawls   "https://rawls.dsde-prod.broadinstitute.org/api/workspaces"
        url     (str/join "/" [rawls workspace "snapshots"])
        limit   23
        request {:method       :get     ; :debug true :debug-body true
                 :url          url
                 :headers      (auth/get-auth-header)
                 :content-type :application/json}]
    (letfn [(more [offset]
              (let [head (-> request
                             (assoc :query-params {:limit  limit
                                                   :offset offset})
                             http/request
                             util/response-body-json
                             :resources)]
                (lazy-cat head (when (pos? (count head))
                                 (more (+ limit offset))))))]
      (more 0))))

(comment
  (snapshots "wfl-dev/CDC_Viral_Sequencing")
  )

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

;; https://cromwell.readthedocs.io/en/stable/execution/ExecutionTwists/
;; :NoNewCalls is the default.
;;
(def a-rawls-submission
  {:entityName                    "string"
   :entityType                    "string"
   :expression                    "string"
   :methodConfigurationName       "string"
   :methodConfigurationNamespace  "string"
   :deleteIntermediateOutputFiles true
   :useCallCache                  true
   :useReferenceDisks             true
   :workflowFailureMode           #{:ContinueWhilePossible :NoNewCalls}})

(defn validate-submission
  "Validate SUBMISSION in WORKSPACE namespace/name."
  [workspace submission]
  (-> {:method       :post
       :url          (workspace-api-url workspace "submissions" "validate")
       :headers      (auth/get-auth-header)
       :content-type :application/json
       :body         (json/write-str submission :escape-slash false)}
      http/request
      util/response-body-json))

(defn create-submission
  "Run SUBMISSION in WORKSPACE name/namespace."
  [workspace submission]
  (-> {:method       :post
       :url          (workspace-api-url workspace "submissions")
       :headers      (auth/get-auth-header)
       :content-type :application/json
       :body         (json/write-str submission :escape-slash false)}
      http/request
      util/response-body-json))

(defn submission-status
  "Status for SUBMISSION-ID in WORKSPACE name/namespace."
  [workspace submission-id]
  {:pre (spec/uuid-string? submission-id)}
  (-> {:method       :get
       :url          (workspace-api-url workspace "submissions" submission-id)
       :headers      (auth/get-auth-header)
       :content-type :application/json}
      http/request
      util/response-body-json))

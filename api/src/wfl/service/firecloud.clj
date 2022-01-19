(ns wfl.service.firecloud
  "Analyze data in Terra using the Firecloud/Terra API."
  (:require [clj-http.client   :as http]
            [clojure.data.json :as json]
            [clojure.string    :as str]
            [wfl.auth          :as auth]
            [wfl.environment   :as env]
            [wfl.log           :as log]
            [wfl.util          :as util])
  (:import [clojure.lang ExceptionInfo]
           [java.util UUID]
           [wfl.util UserException]))

(defn ^:private firecloud-url [& parts]
  (let [url (util/de-slashify (env/getenv "WFL_FIRECLOUD_URL"))]
    (str/join "/" (cons url parts))))

(def ^:private workspace-api-url
  (partial firecloud-url "api/workspaces"))

(defn ^:private get-workspace-json [& parts]
  (-> (apply workspace-api-url parts)
      (http/get {:headers (auth/get-auth-header)})
      util/response-body-json))

(defn workspace-or-throw
  "Return the `workspace` or throw a UserException."
  [workspace]
  {:pre [(some? workspace)]}
  (try
    (get-workspace-json workspace)
    (catch ExceptionInfo cause
      (throw (UserException.
              "Cannot access workspace"
              {:workspace workspace
               :status    (-> cause ex-data :status)}
              cause)))))

(defn abort-submission
  "Abort the submission with `submission-id` in the Terra `workspace`."
  [workspace submission-id]
  (http/delete
   (workspace-api-url workspace "submissions" submission-id)
   {:headers (auth/get-auth-header)}))

(defn create-submission
  "Submit samples in a workspace for analysis with a method configuration in Terra."
  ([workspace methodconfig [entity-type entity-name :as _entity] body-override]
   {:pre [(map? body-override)]}
   (let [[mcns mcn] (str/split methodconfig #"/")
         payload    (util/deep-merge {:methodConfigurationNamespace mcns
                                      :methodConfigurationName mcn
                                      :entityType entity-type
                                      :entityName entity-name
                                      :useCallCache true}
                                     body-override)]
     (-> (workspace-api-url workspace "submissions")
         (http/post {:headers      (auth/get-auth-header)
                     :content-type :application/json
                     :body         (json/write-str payload
                                                   :escape-slash false)})
         util/response-body-json)))
  ([workspace methodconfig entity]
   (create-submission workspace methodconfig entity {})))

(defn submit-method
  "Submit the`methodconfig` for processing in the Terra `workspace`."
  ([workspace methodconfig]
   (submit-method workspace methodconfig ""))
  ([workspace methodconfig usercomment]
   {:pre [(every? string? [workspace methodconfig usercomment])]}
   (let [[mcns mcn] (str/split methodconfig #"/")]
     (-> (workspace-api-url workspace "submissions")
         (http/post {:headers      (auth/get-auth-header)
                     :content-type :application/json
                     :body         (json/write-str
                                    {:methodConfigurationNamespace mcns
                                     :methodConfigurationName mcn
                                     :useCallCache true
                                     :userComment usercomment}
                                    :escape-slash false)})
         util/response-body-json))))

(defn create-workspace
  "Create an empty Terra workspace with the fully-qualified `workspace` name,
   granting access to the `firecloud-group`."
  [workspace firecloud-group]
  (let [[namespace name] (str/split workspace #"/")
        payload {:namespace           namespace
                 :name                name
                 :attributes          {:description ""}
                 :authorizationDomain [{:membersGroupName firecloud-group}]}]
    (-> (workspace-api-url)
        (http/post {:headers      (auth/get-auth-header)
                    :content-type :application/json
                    :body         (json/write-str payload)})
        util/response-body-json)))

(defn clone-workspace
  "Clone the Terra workspace `workspace-to-clone` as `workspace` and grant
   access to the `firecloud-group`."
  [workspace-to-clone workspace firecloud-group]
  {:pre [(every? string? [workspace-to-clone workspace firecloud-group])]}
  (let [[namespace name] (str/split workspace #"/")
        payload {:namespace           namespace
                 :name                name
                 :attributes          {:description ""}
                 :authorizationDomain [{:membersGroupName firecloud-group}]}]
    (-> (workspace-api-url workspace-to-clone "clone")
        (http/post {:headers      (auth/get-auth-header)
                    :content-type :application/json
                    :body         (json/write-str payload)})
        util/response-body-json)))

(defn share-workspace
  "Share the Terra workspace `workspace-to-share` with `email`."
  [workspace-to-share share-info]
  (util/response-body-json
   (http/request {:method :patch
                  :url (workspace-api-url workspace-to-share "acl")
                  :headers      (auth/get-auth-header)
                  :content-type :application/json
                  :body         (json/write-str (map #(merge % {:canShare false
                                                                :canCompute true})
                                                     share-info))
                  :query-params {:inviteUsersNotFound false}})))

(defn delete-workspace
  "Delete the terra `workspace` and all data within."
  [workspace]
  (-> (workspace-api-url workspace)
      (http/delete {:headers (auth/get-auth-header)})
      util/response-body-json))

(defn get-submission
  "Return the submission in the Terra `workspace` with `submission-id`."
  [workspace submission-id]
  (get-workspace-json workspace "submissions" submission-id))

;; Further work needed if we needed to specify multiple `include-key`s.
;;
(defn get-workflow
  "Query the `firecloud-url` for the the `workflow` created by the `submission`
   in the Terra `workspace`, optionally restricting output to `include-key`."
  ([workspace submission-id workflow-id include-key]
   (let [parts [workspace "submissions" submission-id "workflows" workflow-id]]
     (-> (apply workspace-api-url parts)
         (http/get {:headers      (auth/get-auth-header)
                    :query-params (when include-key {:includeKey include-key})})
         (util/response-body-json))))
  ([workspace submission-id workflow-id]
   (get-workflow workspace submission-id workflow-id nil)))

(defn get-workflow-outputs
  "Query the `firecloud-url` for the outputs of the `workflow` created by
   the `submission` in the Terra `workspace`."
  [workspace submission-id workflow-id]
  (get-workspace-json workspace "submissions" submission-id "workflows" workflow-id "outputs"))

(defn get-entity
  "Fetch the `entity` metadata from the `workspace`."
  [workspace [type name :as _entity]]
  (get-workspace-json workspace "entities" type name))

(defn delete-entities
  "Delete the `entities` from the Terra `workspace`.
   Parameters
   ----------
     workspace - Terra Workspace to delete entities from
     entities  - list of entity `[type name]` pairs"
  [workspace entities]
  (log/debug {:action    "Deleting entities"
              :workspace workspace
              :entities  entities})
  (let [make-entity (partial zipmap [:entityType :entityName])]
    (-> (workspace-api-url workspace "entities" "delete")
        (http/post {:headers      (auth/get-auth-header)
                    :content-type :application/json
                    :body         (json/write-str (map make-entity entities))})
        util/response-body-json)))

(defn import-entities
  "Import sample entities into a Terra WORKSPACE from a tsv FILE.
   The upload requires owner permission on the workspace.

   Parameters
   ----------
     workspace  - Terra Workspace to upload samples to.
     file       - A tsv file (or bytes) containing sample inputs.

   Example
   -------
     (import-entities \"workspace-namespace/workspace-name\" \"./samples.tsv\")"
  [workspace file]
  (http/post
   (workspace-api-url workspace "flexibleImportEntities")
   {:headers   (auth/get-auth-header)
    :multipart (util/multipart-body
                {:Content/type "text/tab-separated-values"
                 :entities     (slurp file)})}))

(defn import-entity-set
  "
  Format existing same-typed ENTITIES into a Terra-compatible tsv.
  Upload to \"<Type>_set\" table in WORKSPACE under ENTITY-SET-NAME.

  Parameters
  ----------
    workspace        - Terra Workspace \"namespace/name\"
    entity-set-name  - Identifier for the entity set
    entities         - List of same-typed entity [Type Name] pairs

  Example
  -------
    (import-entity-set \"workspace-namespace/workspace-name\"
                       \"c25ee04b-...\"
                       [[\"sample\" \"NA12878\"] [\"sample\" \"NA12879\"]])
  "
  [workspace entity-set-name [[entity-type _] & _ :as entities]]
  (->> (for [[_ entity-name] entities] [entity-set-name entity-name])
       (util/columns-rows->terra-tsv :membership [entity-type entity-type])
       .getBytes
       (import-entities workspace)))

(defn create-submission-for-entity-set
  "
  Consolidate ENTITIES into an entity set and use it to create a
  submission in WORKSPACE with METHODCONFIG.

  Parameters
  ----------
    workspace       - Terra Workspace \"namespace/name\"
    methodconfig    - Terra Method Configuration \"namespace/name\"
    entities        - List of 1+ entity [Type Name] pairs
    entity-set-name - [Optional] ID for entity set entry, or
                      random UUID if unspecified.

  Example
  -------
    (create-submission-for-entity-set \"w-namespace/w-name\"
                                      \"mc-namespace/mc-name\"
                                      [[\"sample\" \"NA12878\"] [\"sample\" \"NA12879\"]])
  "
  ([workspace methodconfig entities entity-set-name]
   ;; TODO: refactor create-submission to support entity set specification.
   {:pre [(seq entities)]}
   (let [entity-set-type ((import-entity-set workspace entity-set-name entities) :body)]
     (->> (str "this." (util/unsuffix entity-set-type "_set") "s")
          (array-map :expression)
          (create-submission workspace methodconfig [entity-set-type entity-set-name]))))
  ([workspace methodconfig entities]
   (create-submission-for-entity-set workspace methodconfig entities (str (UUID/randomUUID)))))

(defn list-method-configurations
  "List all method configurations in the `workspace`."
  [workspace]
  (get-workspace-json workspace "methodconfigs?allRepos=true"))

(defn method-configuration
  "Return the `methodconfig` in the `workspace`."
  [workspace methodconfig]
  {:pre [(every? some? [workspace methodconfig])]}
  (try
    (get-workspace-json workspace "method_configs" methodconfig)
    (catch ExceptionInfo cause
      (throw (UserException.
              "Cannot access method configuration in workspace"
              {:workspace           workspace
               :methodConfiguration methodconfig
               :status              (-> cause ex-data :status)}
              cause)))))

(defn update-method-configuration
  "Update the method-configuration `method-config-name` to be `methodconfig` in
   the `workspace`."
  [workspace method-config-name methodconfig]
  (-> (workspace-api-url workspace "method_configs" method-config-name)
      (http/put {:headers      (auth/get-auth-header)
                 :content-type :application/json
                 :body         (json/write-str methodconfig)})
      (util/response-body-json)))

(defn list-entities
  "List all entities with `entity-type` in `workspace`."
  [workspace entity-type]
  (get-workspace-json workspace "entities" entity-type))

(defn list-entity-types
  "List the entity types along with their attributes in `workspace`."
  [workspace]
  (get-workspace-json workspace "entities"))

(defn describe-workflow
  "Get a machine-readable description of the `workflow`, including its inputs
   and outputs. `workflow` can either be a url or the workflow source code."
  [workflow]
  (letfn [(url? [s] (some #(str/starts-with? s %) ["http://" "https://"]))]
    (-> (firecloud-url "/api/womtool/v1/describe")
        (http/post {:headers   (auth/get-auth-header)
                    :multipart (util/multipart-body {(if (url? workflow)
                                                       :workflowUrl
                                                       :workflowSource)
                                                     workflow})})
        util/response-body-json)))

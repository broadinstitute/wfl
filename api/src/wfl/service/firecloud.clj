(ns wfl.service.firecloud
  "Analyze data in Terra using the Firecloud/Terra API."
  (:require [clj-http.client :as http]
            [clojure.data.json :as json]
            [clojure.string :as str]
            [wfl.auth :as auth]
            [wfl.environment :as env]
            [wfl.util :as util])
  (:import [java.util UUID]))

(defn ^:private firecloud-url [& parts]
  (let [url (util/de-slashify (env/getenv "WFL_FIRECLOUD_URL"))]
    (str/join "/" (cons url parts))))

(def ^:private workspace-api-url
  (partial firecloud-url "api/workspaces"))

(defn ^:private get-workspace-json [& parts]
  (-> (apply workspace-api-url parts)
      (http/get {:headers (auth/get-auth-header)})
      util/response-body-json))

(defn abort-submission
  "Abort the submission with `submission-id` in the Terra `workspace`."
  [workspace submission-id]
  (-> (workspace-api-url workspace "submissions" submission-id)
      (http/delete {:headers (auth/get-auth-header)})))

(defn create-submission
  "Submit samples in a workspace for analysis with a method configuration in Terra."
  [workspace methodconfig [entity-type entity-name :as _entity]]
  (let [[mcns mcn] (str/split methodconfig #"/")]
    (-> {:method       :post
         :url          (workspace-api-url workspace "submissions")
         :headers      (auth/get-auth-header)
         :content-type :application/json
         :body         (json/write-str
                        {:methodConfigurationNamespace mcns
                         :methodConfigurationName mcn
                         :entityType entity-type
                         :entityName entity-name
                         :useCallCache true}
                        :escape-slash false)}
        http/request
        util/response-body-json
        :submissionId)))

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

(defn get-workflow
  "Query the `firecloud-url` for the the `workflow` created by the `submission`
   in the Terra `workspace`."
  [workspace submission-id workflow-id]
  (get-workspace-json workspace "submissions" submission-id "workflows" workflow-id))

(defn get-workflow-outputs
  "Query the `firecloud-url` for the outputs of the `workflow` created by
   the `submission` in the Terra `workspace`."
  [workspace submission-id workflow-id]
  (get-workspace-json workspace "submissions" submission-id "workflows" workflow-id "outputs"))

(defn get-workflow-status-by-entity
  "Get workflow status given a Terra submission-id and entity-name."
  [workspace {:keys [uuid inputs] :as _item}]
  (let [name (:entity-name inputs)]
    (->> (get-submission workspace uuid)
         :workflows
         (filter #(= name (get-in % [:workflowEntity :entityName])))
         first
         :status)))

(defn delete-entities
  "Delete the `entities` from the Terra `workspace`.
   Parameters
   ----------
     workspace - Terra Workspace to delete entities from
     entities  - list of entity `[type name]` pairs"
  [workspace entities]
  (letfn [(make-entity [[type name]] {:entityType type :entityName name})]
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
  (-> (workspace-api-url workspace "flexibleImportEntities")
      (http/post {:headers   (auth/get-auth-header)
                  :multipart (util/multipart-body
                              {:Content/type "text/tab-separated-values"
                               :entities     (slurp file)})})))

(defn import-entity-set
  "
  Formats existing sample ENTITIES into a Terra-compatible tsv
  and uploads to WORKSPACE as an entity set entry identified by SET-NAME.

  All entity references should correspond to existing entities.
  All entity references should be of the same type.

  Parameters
  ----------
    workspace - Fully-qualified Terra Workspace in which the entity set
                will be created
    set-name  - Unique identifier for the entity set
    entities  - List of entity [Type Name] pairs

  Example
  -------
    (import-entity-set \"workspace-namespace/workspace-name\"
                       \"c25ee04b-...\"
                       [[\"sample\" \"NA12878\"] [\"sample\" \"NA12879\"]])
  "
  [workspace set-name [[entity-type _] & _ :as entities]]
  ;; TODO: Should we enforce common entity-type for all entities?
  (let [columns [entity-type entity-type]
        rows (for [[_ entity-name] entities] [set-name entity-name])]
    (->> (util/columns-rows->terra-tsv :membership columns rows)
         .getBytes
         (import-entities workspace))))

(defn consolidate-entities-to-set
  "
  Optionally consolidates ENTITIES into an entity set.
  Returns the [Type Name] pair for the resulting entity,
  or the passed-in entity if it was the only one specified.

  Parameters
  ----------
    workspace - Fully-qualified Terra Workspace in which the entity set
                would be created
    entities  - List of entity [Type Name] pairs

  Example
  -------
    (consolidate-entities-to-set \"workspace-namespace/workspace-name\"
                                 [[\"sample\" \"NA12878\"] [\"sample\" \"NA12879\"]])
  "
  [workspace [entity & maybe-entities :as entities]]
  ;; TODO: Should we enforce common entity-type for all entities?
  (if (seq maybe-entities)
    (let [id (str (UUID/randomUUID)) ; TODO: do we have access to a workload-affiliated UUID?
          response (import-entity-set workspace id entities)]
      [(:body response) id])
    entity))

(defn create-submission-for-entity-set
  "
  Optionally consolidates ENTITIES into an entity set and uses it to create a
  submission in WORKSPACE with METHODCONFIG.

  At least one entity should be specified.

  Parameters
  ----------
    workspace    - Fully-qualified Terra Workspace in which the submission
                   will be created.
    methodconfig - Fully-qualified method configuration.
    entities     - [Optional] Entity [Type Name] pairs
  "
  [workspace methodconfig & entities]
  ;; TODO: refactor create-submission to support entity set specification.
  ;; TODO: instead of variadic approach, should we take in a list of entities?
  {:pre [(seq entities)]}
  (->> (consolidate-entities-to-set workspace entities)
       (create-submission workspace methodconfig)))

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

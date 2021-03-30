(ns wfl.service.firecloud
  "Analyze data in Terra using the Firecloud/Terra API."
  (:require [clj-http.client :as http]
            [clojure.data.json :as json]
            [clojure.string :as str]
            [wfl.auth :as auth]
            [wfl.environment :as env]
            [wfl.util :as util]))

(defn ^:private firecloud-url [& parts]
  (let [url (util/de-slashify (env/getenv "WFL_FIRECLOUD_URL"))]
    (str/join "/" (conj parts url))))

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
         :url          (workspace-api-url workspace "/submissions")
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

(defn get-workspace
  "Get a single `workspace`'s details"
  [workspace]
  (get-workspace-json workspace))

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
                    :multipart (util/multipart-body
                                (if (url? workflow)
                                  {:workflowUrl workflow}
                                  {:workflowSource workflow}))})
        util/response-body-json)))

(ns wfl.service.firecloud
  "Analyze data in Terra using the Firecloud/Terra API."
  (:require [clj-http.client :as http]
            [clojure.data.json :as json]
            [clojure.string :as str]
            [wfl.auth :as auth]
            [wfl.environment :as env]
            [wfl.util :as util]))

(defn ^:private firecloud-url [& parts]
  (let [url (util/slashify (env/getenv "WFL_FIRECLOUD_URL"))]
    (apply str url parts)))

(def workspace-api-url (partial firecloud-url "api/workspaces/"))

(defn abort-submission
  "Abort the submission with `submission-id` in the Terra `workspace`."
  [workspace submission-id]
  (-> (workspace-api-url (str/join "/" [workspace "submissions" submission-id]))
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

;; OLIVIA START
;; Second pass: creates one submission in a workspace encompassing all specified entities (1+).
;; Third pass: consolidate to single method supporting 1+ entities.

(defn create-submissions
  "First pass:
  Creates one submission in `workspace` for each element of `_entities` using the specified
  `methodconfig`.

  QUESTIONS:
  Should we enforce entity count > 0?
  Is `entity` a reserved word?
  Should I hang tight for Ed's PR to be merged?
  Does FireCloud support submission creation with multiple entities?

  TODO:
  Add specifications for inputs, usage examples"
  [workspace methodconfig & _entities]
  {:pre [(seq _entities)]} ; Checks for at least one entity specified.  Desired?
  (for [_entity _entities] (create-submission workspace methodconfig _entity)))

;; OLIVIA END

(defn get-submission
  "Return the submission in the Terra `workspace` with `submission-id`."
  [workspace submission-id]
  (-> (workspace-api-url workspace "/submissions/" submission-id)
      (http/get {:headers (auth/get-auth-header)})
      util/response-body-json))

(defn get-workflow
  "Query the `firecloud-url` for the the `workflow` created by the `submission`
   in the Terra `workspace`."
  [workspace submission-id workflow-id]
  (-> (str/join "/" [workspace "submissions" submission-id "workflows" workflow-id])
      workspace-api-url
      (http/get {:headers (auth/get-auth-header)})
      util/response-body-json))

(defn get-workflow-outputs
  "Query the `firecloud-url` for the outputs of the `workflow` created by
   the `submission` in the Terra `workspace`."
  [workspace submission-id workflow-id]
  (-> (str/join "/" [workspace "submissions" submission-id "workflows" workflow-id "outputs"])
      workspace-api-url
      (http/get {:headers (auth/get-auth-header)})
      util/response-body-json))

(defn get-workflow-status-by-entity
  "Get workflow status given a Terra submission-id and entity-name."
  [workspace {:keys [uuid inputs] :as _item}]
  (let [name (:entity-name inputs)]
    (->> (get-submission workspace uuid)
         :workflows
         (filter #(= name (get-in % [:workflowEntity :entityName])))
         first
         :status)))

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
  (-> (workspace-api-url workspace "/flexibleImportEntities")
      (http/post {:headers   (auth/get-auth-header)
                  :multipart (util/multipart-body
                              {:Content/type "text/tab-separated-values"
                               :entities     (slurp file)})})))

(defn describe-workflow
  "Get a machine-readbale description of the `workflow`, including its inputs
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

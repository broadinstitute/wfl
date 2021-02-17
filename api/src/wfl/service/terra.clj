(ns wfl.service.terra
  "Analyze data in Terra using the Firecloud/Terra API."
  (:require [clj-http.client   :as http]
            [clojure.data.json :as json]
            [clojure.string    :as str]
            [wfl.auth          :as auth]
            [wfl.util          :as util]))

(defn workspace-api-url
  [terra-url workspace]
  (str terra-url "/api/workspaces/" workspace))

(defn create-submission
  "Submit samples in a workspace for analysis with a method configuration in Terra."
  [terra-url workspace method-configuration-name
   method-configuration-namespace entity-type entity-name]
  (let [workspace-url (workspace-api-url terra-url workspace)
        submission-url (str workspace-url "/submissions")]
    (->
     (http/post
      submission-url
      {:content-type :application/json
       :headers   (auth/get-auth-header)
       :body      (json/write-str
                   {:methodConfigurationNamespace method-configuration-namespace
                    :methodConfigurationName method-configuration-name
                    :entityType entity-type
                    :entityName entity-name
                    :useCallCache true} :escape-slash false)})
     :body
     (util/parse-json)
     :submissionId)))

(defn get-submission
  "Get information about a Terra Cromwell submission."
  [terra-url workspace submission-id]
  (let [workspace-url (workspace-api-url terra-url workspace)
        submission-url (str workspace-url "/submissions/" submission-id)
        response (http/get submission-url {:headers (auth/get-auth-header)})]
    (util/parse-json (:body response))))

(defn get-workflow
  "Query the `firecloud-url` for the the `workflow` created by the `submission`
   in the Terra `workspace`."
  [firecloud-url workspace submission-id workflow-id]
  (-> (workspace-api-url firecloud-url workspace)
      (str (str/join "/" ["" "submissions" submission-id "workflows" workflow-id]))
      (http/get {:headers (auth/get-auth-header)})
      util/response-body-json))

(defn get-workflow-outputs
  "Query the `firecloud-url` for the outputs of the `workflow` created by
   the `submission` in the Terra `workspace`."
  [firecloud-url workspace submission-id workflow-id]
  (-> (workspace-api-url firecloud-url workspace)
      (str (str/join "/" ["" "submissions" submission-id "workflows" workflow-id "outputs"]))
      (http/get {:headers (auth/get-auth-header)})
      util/response-body-json))

(defn get-workflow-status-by-entity
  "Get workflow status given a Terra submission-id and entity-name."
  [terra-url workspace {:keys [uuid inputs] :as _item}]
  (->> (get-submission terra-url workspace uuid)
       :workflows
       (filter #(= (:entity-name inputs) (get-in % [:workflowEntity :entityName])))
       (first)
       (:status)))

(defn import-entities
  "Import sample entities into a Terra WORKSPACE from a tsv FILE.
   The upload requires owner permission on the workspace.

   Parameters
   ----------
   terra-url  - The URL of Terra instance.
   workspace  - Terra Workspace to upload samples to.
   file       - A tsv file (or bytes) containing sample inputs.

   Example
   -------
     (import-entities
         \"https://firecloud-orchestration.dsde-dev.broadinstitute.org\"
         \"general-dev-billing-account/hornet-test\"
         \"./samples.tsv\")"
  [terra-url workspace file]
  (-> (workspace-api-url terra-url workspace)
      (str "/flexibleImportEntities")
      (http/post {:headers (auth/get-auth-header)
                  :multipart    [{:name "Content/type"
                                  :content "text/tab-separated-values"}
                                 {:name "entities"
                                  :content (slurp file)}]})))

(defn describe-wdl
  "Use `firecloud-url` to describe the WDL at `wdl-url`"
  [firecloud-url wdl-url]
  (-> (str firecloud-url "/api/womtool/v1/describe")
      (http/post {:headers   (auth/get-auth-header)
                  :multipart (util/multipart-body
                              {:workflowUrl         wdl-url
                               :workflowTypeVersion "1.0"
                               :workflowType        "WDL"})})
      util/response-body-json))

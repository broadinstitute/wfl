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
  [workspace methodconfig [etype ename]]
  (let [[mcns mcn] (str/split methodconfig #"/")]
    (-> {:method       :post
         :url          (workspace-api-url workspace "/submissions")
         :headers      (auth/get-auth-header)
         :content-type :application/json
         :body         (json/write-str
                        {:methodConfigurationNamespace mcns
                         :methodConfigurationName mcn
                         :entityType etype
                         :entityName ename
                         :useCallCache true}
                        :escape-slash false)}
        http/request
        util/response-body-json
        :submissionId)))

(defn get-submission
  "Get information about a Terra Cromwell submission."
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
  (->> (get-submission workspace uuid)
       :workflows
       (filter #(= (:entity-name inputs) (get-in % [:workflowEntity :entityName])))
       first
       :status))

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
  [workspace file]
  (-> (workspace-api-url workspace "/flexibleImportEntities")
      (http/post {:headers (auth/get-auth-header)
                  :multipart    [{:name "Content/type"
                                  :content "text/tab-separated-values"}
                                 {:name "entities"
                                  :content (slurp file)}]})))

(defn describe-wdl
  "Use `firecloud-url` to describe the WDL at `wdl-url`"
  [wdl-url]
  (-> (firecloud-url "/api/womtool/v1/describe")
      (http/post {:headers   (auth/get-auth-header)
                  :multipart (util/multipart-body
                              {:workflowUrl         wdl-url
                               :workflowTypeVersion "1.0"
                               :workflowType        "WDL"})})
      util/response-body-json))

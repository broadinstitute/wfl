(ns wfl.service.terra
  "Analyze data in Terra using the Firecloud/Terra API."
  (:require [clojure.data.json :as json]
            [clojure.string :as str]
            [clj-http.client :as http]
            [wfl.once :as once]
            [wfl.util :as util]))

(defn workspace-api-url
  [terra-url workspace-namespace workspace-name]
  (str terra-url "/api/workspaces/" workspace-namespace "/" workspace-name))

(defn create-submission
  "Submit samples in a workspace for analysis with a method configuration in Terra."
  [terra-url workspace-namespace workspace-name method-configuration-name
   method-configuration-namespace entity-type entity-name]
  (let [workspace-url (workspace-api-url terra-url workspace-namespace workspace-name)
        submission-url (str workspace-url "/submissions")]
    (->
      (http/post
        submission-url
        {:content-type :application/json
         :headers   (once/get-auth-header)
         :body      (json/write-str
                      {
                       :methodConfigurationNamespace method-configuration-namespace
                       :methodConfigurationName method-configuration-name
                       :entityType entity-type
                       :entityName entity-name
                       :useCallCache true
                       } :escape-slash false)})
      :body
      (util/parse-json)
      :submissionId)))

(defn get-submission
  "Get information about a Terra Cromwell submission."
  [terra-url workspace-namespace workspace-name submission-id]
  (let [workspace-url (workspace-api-url terra-url workspace-namespace workspace-name)
        submission-url (str workspace-url "/submissions/" submission-id)
        response (http/get submission-url {:headers (once/get-auth-header)})]
      (util/parse-json (:body response))))


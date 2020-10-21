(ns wfl.service.terra
  "Analyze data in Terra using the Firecloud/Terra API."
  (:require [clojure.data.json :as json]
            [clojure.string :as str]
            [clj-http.client :as http]
            [wfl.once :as once]))

(defn workspace-api-url
  [terra-url workspace-namespace workspace-name]
  (str terra-url "/api/workspaces/" workspace-namespace "/" workspace-name))

(defn create-submission
  "Submit samples in a workspace for analysis with a method configuration in Terra."
  [terra-url workspace-namespace workspace-name method-configuration-name
   method-configuration-namespace entity-type entity-name]
  (let [workspace-url (workspace-api-url terra-url workspace-namespace workspace-name)]
    (-> {:method    :post
         :url       (str workspace-url "/submissions")
         :content-type :application/json
         :headers   (once/get-auth-header)
         :body      (json/write-str {
                    :methodConfigurationNamespace method-configuration-namespace
                    :methodConfigurationName method-configuration-name
                    :entityType entity-type
                    :entityName entity-name
                    :useCallCache true
                   } :escape-slash false)}
      http/request :body
      (json/read-str :key-fn keyword)
      :submissionId)))

(defn get-submission
  "Get information about a Terra Cromwell submission."
  [terra-url workspace-namespace workspace-name submission-id]
  (let [workspace-url (workspace-api-url terra-url workspace-namespace workspace-name)]
    (-> {:method  :get
         :url     (str workspace-url "/submissions/" submission-id)
         :headers (once/get-auth-header)}
      http/request :body
      (json/read-str :key-fn keyword))))

(comment
  (let [terra-url "https://firecloud-orchestration.dsde-dev.broadinstitute.org"
        workspace-namespace "general-dev-billing-account"
        workspace-name "wfl-integration"
        method-configuration-name "ExternalWholeGenomeReprocessing"
        method-configuration-namespace "general-dev-billing-account"
        entity-type "sample"
        entity-name "NA12878_PLUMBING"
        submission-id "933129e1-4945-4ad5-9fd5-933d0a70f21c"]
    (create-submission terra-url workspace-namespace workspace-name method-configuration-name
                       method-configuration-namespace entity-type entity-name)
    (get-submission terra-url workspace-namespace workspace-name submission-id)))

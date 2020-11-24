(ns wfl.service.terra
  "Analyze data in Terra using the Firecloud/Terra API."
  (:require [clojure.data.json :as json]
            [clojure.tools.logging :as log]
            [clojure.string :as str]
            [clj-http.client :as http]
            [wfl.once :as once]
            [wfl.util :as util])
  (:import [java.util.concurrent TimeUnit]))

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
       :headers   (once/get-auth-header)
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
        response (http/get submission-url {:headers (once/get-auth-header)})]
    (util/parse-json (:body response))))

(defn wait-for-workflow-ids
  "Poll a Terra Cromwell submission until there is a uuid for each workflow."
  [terra-url workspace submission-id]
  (loop [terra-url terra-url submission-id submission-id]
    (let [seconds 15
          now (:workflows (get-submission terra-url workspace submission-id))]
      (if (not (= (count (remove nil? (map :workflowId now))) (count now)))
        (do (log/info "%s: Sleeping %s seconds on submission-id: %s"
                       submission-id seconds now)
            (.sleep TimeUnit/SECONDS seconds)
            (recur terra-url submission-id))
        (:workflows (get-submission terra-url workspace submission-id))))))

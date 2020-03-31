(ns zero.wgs-test
  (:require [clojure.data.json :as json]
            [buddy.sign.jwt :as jwt]
            [zero.service.cromwell :as cromwell]
            [zero.environments :as env]
            [zero.service.gcs :as gcs]
            [zero.util :as util])
  (:import (java.util UUID)))

(def wfl-url
  "http://localhost:3000")

(def test-input-path
  "gs://broad-gotc-test-storage/single_sample/plumbing/bams/2m/")

(def test-output-path
  (str "gs://broad-gotc-dev-zero-test/wgs-test-output/" (UUID/randomUUID) "/"))

(def credentials-path
  (get-in (env/stuff :gotc-dev) [:server :vault]))

(defn create-jwt
  []
  (let [{:keys [oauth2_client_id oauth2_client_secret]}
        (util/vault-secrets credentials-path)
        iat (quot (System/currentTimeMillis) 1000)
        exp (+ iat 3600000)
        payload {:hd    "broadinstitute.org"
                 :iss   "https://accounts.google.com"
                 :aud   oauth2_client_id
                 :iat   iat
                 :exp   exp
                 :scope ["openid" "email"]}]
    (jwt/sign payload oauth2_client_secret)))

(defn start-wgs-workflow
  [env max-string input-path output-path]
  (-> {:method       :post
       :url          (str wfl-url "/api/v1/wgs")
       :content-type :application/json
       :headers      {"Authorization" (str "Bearer " (create-jwt))}
       :body         (json/write-str {:environment env
                                      :max         max-string
                                      :input_path  input-path
                                      :output_path output-path})}
      cromwell/request-json :body :results))

(defn -main
  [& args]
  (gcs/create-object test-output-path)
  (let [workflow-results (start-wgs-workflow "wgs-dev" "1" test-input-path test-output-path)
        workflow-id (first workflow-results)
        status (:status (cromwell/wait-for-workflow-complete :wgs-dev workflow-id))]
    (println {:id workflow-id :status status})
    (System/exit (if (= status "Succeeded") 0 1))))

(ns zero.wgs-test
  (:require [clojure.data.json :as json]
            [buddy.sign.jwt :as jwt]
            [zero.service.cromwell :as cromwell]
            [zero.environments :as env]
            [zero.service.gcs :as gcs]
            [zero.util :as util])
  (:import (java.util UUID)))

(def wfl-url
  "WFL server url"
  "http://localhost:3000")

(defn tmp-output-path
  "Temp directory for test outputs"
  [output-path]
  (str output-path (UUID/randomUUID) "/"))

(defn credentials-path
  [env]
  "Vault path for ENV server credentials"
  (get-in (env/stuff env) [:server :vault]))

(defn create-jwt
  "Create a JWT to authenticate WFL API requests."
  [env]
  (let [{:keys [oauth2_client_id oauth2_client_secret]}
        (util/vault-secrets (credentials-path env))
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
  "Start MAX WGS workflows from INPUT-PATH to OUTPUT-PATH in ENV Cromwell."
  [env max input-path output-path]
  (-> {:method       :post
       :url          (str wfl-url "/api/v1/wgs")
       :content-type :application/json
       :headers      {"Authorization" (str "Bearer " (create-jwt (keyword :gotc-dev)))}
       :body         (json/write-str {:environment env
                                      :max         max
                                      :input_path  input-path
                                      :output_path output-path})}
      cromwell/request-json :body :results))

(defn -main
  "Submit a WGS workflow to ENV Cromwell and check if it succeeds."
  [env max input-path output-path]
  (let [test-output-path (tmp-output-path output-path)]
    (gcs/create-object test-output-path)
    (let [workflow-results (start-wgs-workflow env max input-path test-output-path)
          workflow-id (first workflow-results)
          status (:status (cromwell/wait-for-workflow-complete (keyword env) workflow-id))]
      (println {:id workflow-id :status status})
      (gcs/delete-object test-output-path)
      (System/exit (if (= status "Succeeded") 0 1)))))

(comment (-main "wgs-dev"
                "1"
                "gs://broad-gotc-test-storage/single_sample/plumbing/bams/2m/"
                "gs://broad-gotc-dev-zero-test/wgs-test-output/"))

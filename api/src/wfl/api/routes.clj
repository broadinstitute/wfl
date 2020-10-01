(ns wfl.api.routes
  "Define routes for API endpoints"
  (:require [clojure.string                     :as str]
            [muuntaja.core                      :as muuntaja-core]
            [reitit.coercion.spec]
            [reitit.ring                        :as ring]
            [reitit.ring.coercion               :as coercion]
            [reitit.ring.middleware.exception   :as exception]
            [reitit.ring.middleware.multipart   :as multipart]
            [reitit.ring.middleware.muuntaja    :as muuntaja]
            [reitit.ring.middleware.parameters  :as parameters]
            [reitit.swagger                     :as swagger]
            [reitit.swagger-ui                  :as swagger-ui]
            [wfl.api.handlers                   :as handlers]
            [wfl.environments                   :as env]
            [wfl.api.spec                       :as spec]
            [wfl.wfl                            :as wfl]
            [wfl.once                           :as once]))

(def endpoints
  "Endpoints exported by the server."
  [["/"
    {:get  {:no-doc true
            :handler (handlers/success {:status "OK"})}}]
   ["/status"
    {:get  {:summary "Get the status of the server"
            :handler (handlers/success {:status "OK"})
            :responses {200 {:body {:status string?}}}
            :swagger {:tags ["Information"]}}}]
   ["/version"
    {:get  {:summary "Get the versions of server and supported pipelines"
            :handler (handlers/success (let [versions (wfl/get-the-version)
                                             pipeline-versions-keys (keep (fn [x] (when (and (string? x)
                                                                                             (str/ends-with? x ".wdl")) x))
                                                                          (keys versions))]
                                         {:pipeline-versions (select-keys versions pipeline-versions-keys)
                                          :version (apply dissoc versions pipeline-versions-keys)}))
            :responses {200 {:body {:version map?
                                    :pipeline-versions map?}}}
            :swagger {:tags ["Information"]}}}]
   ["/oauth2id"
    {:get {:summary   "Get the OAuth2 Client ID for this deployment of the server"
           :handler   (handlers/success {:oauth2-client-id (once/get-oauth-client-id)})
           :responses {200 {:body {:oauth2-client-id string?}}}
           :swagger   {:tags ["Information"]}}}]
   ["/api/v1/environments"
    {:get  {:summary "Get all of the environments the server knows"
            :parameters nil
            :responses {200 {:body map?}}
            :handler (handlers/success env/stuff)}}]
   ["/api/v1/workflows"
    {:post {:summary    "Query for workflows"
            :parameters {:body ::spec/workflow-request}
            :responses {200 {:body {:results seq?}}}
            :handler    handlers/query-workflows}}]
   ["/api/v1/statuscounts"
    {:get  {:summary "Get the status counts info for a given environment"
            :parameters {:query {:environment string?}}
            :responses {200 {:body {:total pos-int?}}}
            :handler handlers/status-counts}}]
   ["/api/v1/append_to_aou"
    {:post {:summary    "Append to an existing AOU workload."
            :parameters {:body ::spec/append-to-aou-request}
            :responses  {200 {:body ::spec/append-to-aou-response}}
            :handler    handlers/append-to-aou-workload}}]
   ["/api/v1/workload"
    {:get  {:summary    "Get the workloads."
            :parameters {:query ::spec/uuid-query}
            :responses  {200 {:body ::spec/workload-responses}}
            :handler    handlers/get-workload!}}]
   ["/api/v1/create"
    {:post {:summary    "Create a new workload."
            :parameters {:body ::spec/workload-request}
            :responses  {200 {:body ::spec/workload-response}}
            :handler    handlers/post-create}}]
   ["/api/v1/start"
    {:post {:summary    "Start workloads."
            :parameters {:body ::spec/uuid-kvs}
            :responses  {200 {:body ::spec/workload-responses}}
            :handler    handlers/post-start}}]
   ["/api/v1/exec"
    {:post {:summary    "Create and start new workload."
            :parameters {:body ::spec/workload-request}
            :responses  {200 {:body ::spec/workload-response}}
            :handler    handlers/post-exec}}]
   ["/swagger/swagger.json"
    {:get {:no-doc true ;; exclude this endpoint itself from swagger
           :swagger {:info {:title (str wfl/the-name "-API")
                            :version (str (:version (wfl/get-the-version)))}
                     :securityDefinitions {:googleoauth {:type "oauth2"
                                                         :flow "implicit"
                                                         :authorizationUrl "https://accounts.google.com/o/oauth2/auth"
                                                         :scopes {:openid  "open id authorization"
                                                                  :email   "email authorization"
                                                                  :profile "profile authorization"}}}
                     :basePath "/"} ;; prefix for all paths
           :handler (swagger/create-swagger-handler)}}]])

;; :muuntaja is required for showing response body on swagger page.
;;
(def routes
  (ring/ring-handler
   (ring/router
    endpoints
    {:data {:coercion   reitit.coercion.spec/coercion
            :muuntaja   muuntaja-core/instance
            :middleware [parameters/parameters-middleware
                         muuntaja/format-negotiate-middleware
                         muuntaja/format-response-middleware
                         exception/exception-middleware
                         muuntaja/format-request-middleware
                         coercion/coerce-response-middleware
                         coercion/coerce-request-middleware
                         coercion/coerce-exceptions-middleware
                         multipart/multipart-middleware]}})
   (ring/routes
    (swagger-ui/create-swagger-ui-handler {:path "/swagger"
                                           :url  "/swagger/swagger.json"
                                           :root "swagger-ui"
                                           :config {:jsonEditor false}}))))

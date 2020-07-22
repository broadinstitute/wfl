(ns zero.api.routes
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
            [zero.api.handlers                  :as handlers]
            [zero.environments                  :as env]
            [zero.api.spec                      :as spec]
            [zero.zero                          :as zero]))

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
            :handler (handlers/success (let [versions (zero/get-the-version)
                                             pipeline-versions-keys (keep (fn [x] (when (and (string? x)
                                                                                             (str/ends-with? x ".wdl")) x))
                                                                          (keys versions))]
                                         {:pipeline-versions (select-keys versions pipeline-versions-keys)
                                          :version (apply dissoc versions pipeline-versions-keys)}))
            :responses {200 {:body {:version map?
                                    :pipeline-versions map?}}}
            :swagger {:tags ["Information"]}}}]
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
   ["/api/v1/wgs"
    {:post {:summary    "Submit WGS Reprocessing workflows"
            :parameters {:body ::spec/wgs-request}
            :responses  {200 {:body {:results vector?}}}
            :handler    handlers/submit-wgs}}]
   ["/api/v1/append_to_aou"
    {:post {:summary    "Append to an existing AOU workload."
            :parameters {:body ::spec/aou-request}
            :responses  {200 {:body ::spec/append-to-workload-response}}
            :handler    handlers/append-to-aou-workload}}]
   ["/api/v1/workload"
    {:get  {:summary    "Get the workloads."
            :parameters {:query ::spec/uuid-query}
            :responses  {200 {:body ::spec/workload-responses}}
            :handler    handlers/get-workload}}]
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
           :swagger {:info {:title (str zero/the-name "-API")
                            :version (str (:version (zero/get-the-version)))}
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

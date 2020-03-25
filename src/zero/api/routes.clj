(ns zero.api.routes
  "Define routes for API endpoints"
  (:require [clojure.spec.alpha                 :as s]
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
            [zero.zero                          :as zero]))

;; Parameter definitions
;;
(s/def ::cromwell_instance string?)
(s/def ::end               string?)
(s/def ::environment       string?)
(s/def ::input             string?)
(s/def ::input_path        string?)
(s/def ::max               string?)
(s/def ::output            string?)
(s/def ::output_path       string?)
(s/def ::pipeline          #{"ExternalWholeGenomeReprocessing"})
(s/def ::project_id        string?)
(s/def ::start             string?)
(s/def ::table             string?)
(s/def ::workflow-request (s/keys :req-un [::environment ::start ::end]))
(s/def ::workload-request (s/keys :req-un [::cromwell
                                           ::input
                                           ::output
                                           ::pipeline
                                           ::project
                                           ::table]))
(s/def ::wgs-request (s/keys :req-un [::environment
                                      ::max
                                      ::input_path
                                      ::output_path]))

(def endpoints
  "Endpoints exported by the server."
  [["/auth/google"
    {:get {:no-doc true
           :handler (constantly "This route is handled by wrap-oauth2!")}}]
   ["/"
    {:get  {:no-doc true
            :handler (handlers/success {:status "OK"})}}]
   ["/status"
    {:get  {:summary "Get the status of the server"
            :handler (handlers/success {:status "OK"})
            :responses {200 {:body {:status string?}}}
            :swagger {:tags ["Information"]}}}]
   ["/version"
    {:get  {:summary "Get the versions of server and supported pipelines"
            :handler (handlers/success (zero/get-the-version))
            :responses {200 {:body {(keyword zero/the-name) string?
                                    :version string?
                                    :build    pos-int?
                                    :time    string?}}}
            :swagger {:tags ["Information"]}}}]
   ["/api/v1/environments"
    {:get  {:summary "Get all of the environments the server knows"
            :parameters nil
            :responses {200 {:body map?}}
            :handler (handlers/authorize (handlers/success env/stuff))}}]
   ["/api/v1/workflows"
    {:post {:summary    "Query for workflows"
            :parameters {:body ::workflow-request}
            :responses {200 {:body {:results seq?}}}
            :handler    (handlers/authorize handlers/query-workflows)}}]
   ["/api/v1/statuscounts"
    {:get  {:summary "Get the status counts info for a given environment"
            :parameters {:query {:environment string?}}
            :responses {200 {:body {:total pos-int?}}}
            :handler (handlers/authorize handlers/status-counts)}}]
   ["/api/v1/workloads"
    {:get {:summary "Get all workloads for a given environment"
           :parameters {:query {:environment string?}}
           :responses {200 {:body {:results seq?}}}
           :handler handlers/list-workloads}}]
   ["/api/v1/wgs"
    {:post {:summary    "Submit WGS Reprocessing workflows"
            :parameters {:body ::wgs-request}
            :responses  {200 {:body {:results vector?}}}
            :handler    (handlers/authorize handlers/submit-wgs)}}]
   ["/api/v1/workload"
    {:post {:summary "Create a new workload"
            :parameters {:body ::workload-request}
            :responses {200 {:body map?}}
            :handler (handlers/authorize handlers/create-workload)}}]
   ["/swagger.json"
    {:get {:no-doc true ;; exclude this endpoint itself from swagger
           :swagger {:info {:title (str zero/the-name "-API")}
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
                                             :url  "/swagger.json"
                                             :root "swagger-ui"
                                             :config {:jsonEditor false}}))))

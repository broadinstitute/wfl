(ns wfl.api.routes
  "Define routes for API endpoints"
  (:require [clojure.string                     :as str]
            [reitit.ring.middleware.dev         :as dev]
            [reitit.dev.pretty                  :as pretty]
            [muuntaja.core                      :as muuntaja-core]
            [reitit.coercion.spec]
            [reitit.ring                        :as ring]
            [reitit.ring.coercion               :as coercion]
            [reitit.ring.middleware.exception   :as exception]
            [reitit.ring.middleware.multipart   :as multipart]
            [reitit.ring.middleware.muuntaja    :as muuntaja]
            [reitit.ring.middleware.parameters  :as parameters]
            [reitit.swagger                     :as swagger]
            [wfl.api.handlers                   :as handlers]
            [wfl.api.workloads                  :as workloads]
            [wfl.environments                   :as env]
            [wfl.api.spec                       :as spec]
            [wfl.wfl                            :as wfl]
            [wfl.once                           :as once])
  (:import (java.sql SQLException)))

(def endpoints
  "Endpoints exported by the server."
  [["/"
    {:get  {:no-doc true
            :handler (handlers/success {:status "OK"})}}]
   ["/status"
    {:get  {:summary "Get the status of the server"
            :handler (handlers/success {:status "OK"})
            :responses {200 {:body {:status string?}}}
            :swagger {:tags ["Informational"]}}}]
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
            :swagger {:tags ["Informational"]}}}]
   ["/oauth2id"
    {:get {:summary   "Get the OAuth2 Client ID for this deployment of the server"
           :handler   (fn [_] (handlers/succeed {:oauth2-client-id @once/oauth-client-id}))
           :responses {200 {:body {:oauth2-client-id string?}}}
           :swagger   {:tags ["Informational"]}}}]
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
                                                         :scopes {:openid  "Basic OpenID authorization"
                                                                  :email   "Read access to your email"
                                                                  :profile "Read access to your profile"}}}
                     :tags [{:name "Informational"}
                            {:name "Authenticated"}]
                     :basePath "/"} ;; prefix for all paths
           :handler (swagger/create-swagger-handler)}}]])

(defn endpoint-swagger-auth-processor
  "Use the same security-info across all /api endpoints."
  [endpoints]
  (let [security-info {:swagger {:tags ["Authenticated"]
                                 :security [{:googleoauth []}]}}]
    (letfn [(needs-security? [endpoint] (str/starts-with? (first endpoint) "/api"))
            (write-security-info [method-description] (merge-with merge security-info method-description))
            (modify-method [method] (zipmap (keys method) (map write-security-info (vals method))))]
      (vec (map #(if (needs-security? %) (apply vector (first %) (map modify-method (rest %))) %)
                endpoints)))))

;; https://cljdoc.org/d/metosin/reitit/0.5.10/doc/ring/exception-handling-with-ring#exceptioncreate-exception-middleware
;;
(defn ex-handler
  "Top level exception handler. Prefer to use status and message
   from EXCEPTION and fallback to the provided STATUS and MESSAGE."
  [status message exception request]
  {:status (or (:status (ex-data exception)) status)
   :body {:message (or (.getMessage exception) message)
          :exception (.getClass exception)
          :data (ex-data exception)
          :uri (:uri request)}})

(def exception-middleware
  "Custom exception middleware, dispatch on fully qualified exception types."
  (exception/create-exception-middleware
    (merge
      exception/default-handlers
      {;; ex-data with :type ::invalid-pipeline of :wfl/exception
       ::workloads/invalid-pipeline          (partial ex-handler 400 "")
       ;; SQLException and all it's child classes
       SQLException                          (partial ex-handler 500 "SQL Error")
       ;; handle clj-http Slingshot stone exceptions
       :clj-http.client/unexceptional-status (partial ex-handler 400 "HTTP Error on request")
       ;; override the default handler
       ::exception/default                   (partial ex-handler 500 "Internal Server Error")
       ;; print stack-traces for all exceptions in logs
       ::exception/wrap                      (fn [handler e request]
                                               (println "ERROR" (pr-str
                                                                  ;; uncomment to log the full request body
                                                                  ; request
                                                                  (:uri request)))
                                               (handler e request))})))

(def routes
  (ring/ring-handler
    (ring/router
      (endpoint-swagger-auth-processor endpoints)
      {;; uncomment for easier debugging with coercion and middleware transformations
       ;; :reitit.middleware/transform dev/print-request-diffs
       ;; :exception pretty/exception
       :data {:coercion   reitit.coercion.spec/coercion
              :muuntaja   muuntaja-core/instance
              :middleware [;; query-params & form-params
                           parameters/parameters-middleware
                           ;; content-negotiation
                           muuntaja/format-negotiate-middleware
                           ;; encoding response body
                           muuntaja/format-response-middleware
                           exception-middleware
                           ;; decoding request body
                           muuntaja/format-request-middleware
                           ;; coercing response bodys
                           coercion/coerce-response-middleware
                           ;; coercing request parameters
                           coercion/coerce-request-middleware
                           multipart/multipart-middleware]}})
    ;; get more correct http error responses on routes
    (ring/create-default-handler
      {:not-found          (fn [m] {:status 404 :body (format "Route %s not found" (:uri m))})
       :method-not-allowed (fn [m] {:status 405 :body (format "Method %s not allowed" (name (:request-method m)))})})))

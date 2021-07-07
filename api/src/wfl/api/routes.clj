(ns wfl.api.routes
  "Define routes for API endpoints."
  (:require [clojure.string                     :as str]
            [wfl.log                            :as log]
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
            [wfl.environment                    :as env]
            [wfl.api.spec                       :as spec]
            [wfl.module.all                     :as all]
            [wfl.module.aou                     :as aou]
            [wfl.wfl                            :as wfl])
  (:import [java.sql SQLException]
           [wfl.util UserException]
           [org.apache.commons.lang3.exception ExceptionUtils]))

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
    {:get {:summary   "Get the versions of server and supported pipelines"
           :handler   (handlers/success (wfl/get-the-version))
           :responses {200 {:body ::spec/version-response}}
           :swagger   {:tags ["Informational"]}}}]
   ["/oauth2id"
    {:get {:summary   "Get the OAuth2 Client ID deployed for this server"
           :handler   (fn [_] (handlers/succeed
                               {:oauth2-client-id
                                (env/getenv "WFL_OAUTH2_CLIENT_ID")}))
           :responses {200 {:body {:oauth2-client-id string?}}}
           :swagger   {:tags ["Informational"]}}}]
   ["/api/v1/append_to_aou"
    {:post {:summary    "Append to an existing AOU workload."
            :parameters {:body ::aou/append-to-aou-request}
            :responses  {200 {:body ::aou/append-to-aou-response}}
            :handler    handlers/append-to-aou-workload}}]
   ["/api/v1/workload"
    {:get {:summary    "Get the workloads."
           :parameters {:query ::spec/workload-query}
           :responses  {200 {:body ::spec/workload-responses}}
           :handler    handlers/get-workload}}]
   ["/api/v1/workload/:uuid/workflows"
    {:get {:summary    "Get workflows managed by the workload."
           :parameters {:path {:uuid ::all/uuid} :query ::spec/workflow-query}
           :responses  {200 {:body ::spec/workflows}}
           :handler    handlers/get-workflows}}]
   ["/api/v1/workload/:uuid/retry"
    {:post {:summary    "Resubmit workflows in workload by status."
            :parameters {:path {:uuid   ::all/uuid}
                         :body {:status ::all/status}}
            :responses  {200 {:body ::spec/workload-response}}
            :handler    handlers/post-retry}}]
   ["/api/v1/create"
    {:post {:summary    "Create a new workload."
            :parameters {:body ::spec/workload-request}
            :responses  {200 {:body ::spec/workload-response}}
            :handler    handlers/post-create}}]
   ["/api/v1/start"
    {:post {:summary    "Start a workload."
            :parameters {:body ::all/uuid-kv}
            :responses  {200 {:body ::spec/workload-response}}
            :handler    handlers/post-start}}]
   ["/api/v1/stop"
    {:post {:summary    "Stop managing the workload specified by 'request'."
            :parameters {:body ::all/uuid-kv}
            :responses  {200 {:body ::spec/workload-response}}
            :handler    handlers/post-stop}}]
   ["/api/v1/exec"
    {:post {:summary    "Create and start a new workload."
            :parameters {:body ::spec/workload-request}
            :responses  {200 {:body ::spec/workload-response}}
            :handler    handlers/post-exec}}]
   ["/swagger/swagger.json"
    {:get {:no-doc true    ; exclude this endpoint itself from swagger
           :swagger
           {:info {:title (str wfl/the-name "-API")
                   :version (str (:version (wfl/get-the-version)))}
            :securityDefinitions
            {:googleoauth
             {:type "oauth2"
              :flow "implicit"
              :authorizationUrl "https://accounts.google.com/o/oauth2/auth"
              :scopes {:openid  "Basic OpenID authorization"
                       :email   "Read access to your email"
                       :profile "Read access to your profile"}}}
            :tags [{:name "Informational"}
                   {:name "Authenticated"}]
            :basePath "/"}              ; prefix for all paths
           :handler (swagger/create-swagger-handler)}}]])

(defn endpoint-swagger-auth-processor
  "Use the same security-info across all /api endpoints."
  [endpoints]
  (let [security-info {:swagger {:tags ["Authenticated"]
                                 :security [{:googleoauth []}]}}]
    (letfn [(needs-security? [endpoint]
              (str/starts-with? (first endpoint) "/api"))
            (write-security-info [method-description]
              (merge-with merge security-info method-description))
            (modify-method [method]
              (zipmap (keys method)
                      (map write-security-info (vals method))))]
      (vec (map #(if (needs-security? %)
                   (apply vector (first %) (map modify-method (rest %))) %)
                endpoints)))))

;; https://cljdoc.org/d/metosin/reitit/0.5.10/doc/ring/exception-handling-with-ring#exceptioncreate-exception-middleware
;;
(defn exception-handler
  "Top level exception handler. Prefer to use status and message
   from EXCEPTION and fallback to the provided STATUS and MESSAGE."
  [status message exception {:keys [uri] :as _request}]
  {:status (or (:status (ex-data exception)) status)
   :body   (-> (when-let [cause (.getCause exception)]
                 {:cause (ExceptionUtils/getRootCauseMessage cause)})
               (merge {:uri     uri
                       :message (or (.getMessage exception) message)
                       :details (-> exception ex-data (dissoc :status))}))})

(defn logging-exception-handler
  "Like [[exception-handler]] but also log information about the exception."
  [status message exception {:keys [uri] :as request}]
  (let [response (exception-handler status message exception request)]
    (log/errorf "Server %s error at occurred at %s :" (:status response) uri)
    (log/error exception (:body response))
    response))

(def exception-middleware
  "Custom exception middleware, dispatch on fully qualified exception types."
  (exception/create-exception-middleware
   (merge
    exception/default-handlers
    {;; ex-data with :type :wfl/exception
     ::workloads/invalid-pipeline          (partial exception-handler 400 "")
     ::workloads/workload-not-found        (partial exception-handler 404 "")
     UserException                         (partial exception-handler 400 "")
     ;; SQLException and all its child classes
     SQLException                          (partial logging-exception-handler 500 "SQL Exception")
     ;; handle clj-http Slingshot stone exceptions
     :clj-http.client/unexceptional-status (partial exception-handler 400 "HTTP Error on request")
     ;; override the default handler
     ::exception/default                   (partial logging-exception-handler 500 "Internal Server Error")})))

(def routes
  (ring/ring-handler
   (ring/router
    (endpoint-swagger-auth-processor endpoints)
    {;; uncomment to debug coercion and middleware transformations
     ;; :reitit.middleware/transform dev/print-request-diffs
     ;; :exception pretty/exception
     :data {:coercion   reitit.coercion.spec/coercion
            :muuntaja   muuntaja-core/instance
            :middleware [exception-middleware
                         ;; query-params & form-params
                         parameters/parameters-middleware
                         ;; content-negotiation
                         muuntaja/format-negotiate-middleware
                         ;; encoding response body
                         muuntaja/format-response-middleware
                         ;; decoding request body
                         muuntaja/format-request-middleware
                         ;; coercing response bodys
                         coercion/coerce-response-middleware
                         ;; coercing request parameters
                         coercion/coerce-request-middleware
                         multipart/multipart-middleware]}})
   ;; get more correct http error responses on routes
   (ring/create-default-handler
    {:not-found          (fn [m] {:status 404
                                  :body (format "Route %s not found" (:uri m))})
     :method-not-allowed (fn [m] {:status 405
                                  :body (format "Method %s not allowed"
                                                (name (:request-method m)))})})))

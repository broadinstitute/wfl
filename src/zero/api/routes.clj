(ns zero.api.routes
  "Define routes for API endpoints"
  (:require [clojure.spec.alpha                 :as s]
            [clojure.string                     :as str]
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
            [zero.service.cromwell              :as cromwell]
            [zero.util                          :as util]
            [zero.zero                          :as zero])
  (:import [java.util UUID]))

(defn uuid-string? [s] (uuid? (util/do-or-nil (UUID/fromString s))))

(s/def ::base_file_name       string?)
(s/def ::commit               (s/and string? (comp (partial == 40) count)))
(s/def ::created              inst?)
(s/def ::creator              string?)
(s/def ::cromwell             string?)
(s/def ::end                  string?)
(s/def ::environment          string?)
(s/def ::final_gvcf_base_name string?)
(s/def ::finished             inst?)
(s/def ::id                   pos-int?)
(s/def ::input                string?)
(s/def ::input_cram           string?)
(s/def ::input_path           string?)
(s/def ::items                (s/or :aos (s/+ ::items-aos)
                                    :wgs (s/+ ::items-wgs)))
(s/def ::items-aos            (constantly true)) ; stub
(s/def ::items-wgs            (s/keys :opt-un [::base_file_name
                                               ::final_gvcf_base_name
                                               ::unmapped_bam_suffix]
                                      :req-un [::input_cram
                                               ::sample_name]))
(s/def ::max                  pos-int?)
(s/def ::output               string?)
(s/def ::output_path          string?)
(s/def ::pipeline             string?)
(s/def ::project              string?)
(s/def ::release              string?)
(s/def ::sample_name          string?)
(s/def ::start                string?)
(s/def ::started              inst?)
(s/def ::status               (set cromwell/statuses))
(s/def ::unmapped_bam_suffix  string?)
(s/def ::updated              inst?)
(s/def ::uuid                 (s/and string? uuid-string?))
(s/def ::uuid-kv              (s/keys :req-un [::uuid]))
(s/def ::uuid-kvs             (s/* ::uuid-kv))
(s/def ::uuid-query           (s/or :none empty?
                                    :one  (s/keys :req-un [::uuid])))
(s/def ::uuids                (s/* ::uuid))
(s/def ::version              string?)
(s/def ::wdl                  string?)
(s/def ::wgs-request          (s/keys :req-un [::environment
                                               ::max
                                               ::input_path
                                               ::output_path]))
(s/def ::workflow-aos         (constantly true)) ; stub
(s/def ::workflow-wgs         (s/keys :opt-un [::base_file_name
                                               ::final_gvcf_base_name
                                               ::status
                                               ::unmapped_bam_suffix
                                               ::updated
                                               ::uuid]
                                      :req-un [::id
                                               ::input_cram
                                               ::sample_name]))
(s/def ::workflow-request     (s/keys :req-un [::end
                                               ::environment
                                               ::start]))
(s/def ::workflows            (s/or :aos (s/+ ::workflow-aos)
                                    :wgs (s/+ ::workflow-wgs)))
(s/def ::workload-request     (s/keys :req-un [::creator
                                               ::cromwell
                                               ::input
                                               ::items
                                               ::output
                                               ::pipeline
                                               ::project]))
(s/def ::workload-response    (s/keys :opt-un [::finished
                                               ::pipeline
                                               ::started
                                               ::wdl
                                               ::workflows]
                                      :req-un [::commit
                                               ::created
                                               ::creator
                                               ::cromwell
                                               ::id
                                               ::input
                                               ::output
                                               ::project
                                               ::release
                                               ::uuid
                                               ::version]))
(s/def ::workload-responses   (s/* ::workload-response))

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
            :parameters {:body ::workflow-request}
            :responses {200 {:body {:results seq?}}}
            :handler    handlers/query-workflows}}]
   ["/api/v1/statuscounts"
    {:get  {:summary "Get the status counts info for a given environment"
            :parameters {:query {:environment string?}}
            :responses {200 {:body {:total pos-int?}}}
            :handler handlers/status-counts}}]
   ["/api/v1/wgs"
    {:post {:summary    "Submit WGS Reprocessing workflows"
            :parameters {:body ::wgs-request}
            :responses  {200 {:body {:results vector?}}}
            :handler    handlers/submit-wgs}}]
   ["/api/v1/workload"
    {:get  {:summary    "Get the workloads."
            :parameters {:query ::uuid-query}
            :responses  {200 {:body ::workload-responses}}
            :handler    handlers/get-workload}}]
   ["/api/v1/create"
    {:post {:summary    "Create a new workload."
            :parameters {:body ::workload-request}
            :responses  {200 {:body ::workload-response}}
            :handler    handlers/post-create}}]
   ["/api/v1/start"
    {:post {:summary    "Start workloads."
            :parameters {:body ::uuid-kvs}
            :responses  {200 {:body ::workload-responses}}
            :handler    handlers/post-start}}]
   ["/api/v1/exec"
    {:post {:summary    "Create and start new workload."
            :parameters {:body ::workload-request}
            :responses  {200 {:body ::workload-response}}
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

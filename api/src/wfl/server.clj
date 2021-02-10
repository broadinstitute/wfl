(ns wfl.server
  "An HTTP API server."
  (:require [clojure.pprint :refer [pprint]]
            [clojure.stacktrace :refer [print-throwable]]
            [clojure.string :as str]
            [clojure.tools.logging :as log]
            [clj-time.coerce :as tc]
            [ring.adapter.jetty :as jetty]
            [ring.middleware.defaults :as defaults]
            [ring.middleware.json :refer [wrap-json-response]]
            [ring.middleware.params :refer [wrap-params]]
            [ring.middleware.reload :as reload]
            [ring.middleware.session.cookie :as cookie]
            [wfl.api.routes :as routes]
            [wfl.api.workloads :as workloads]
            [wfl.environment :as env]
            [wfl.jdbc :as jdbc]
            [wfl.service.postgres :as postgres]
            [wfl.util :as util]
            [wfl.wfl :as wfl])
  (:import (java.util.concurrent Future TimeUnit)
           (org.postgresql.util PSQLException)))

(def description
  "The purpose of this command."
  (let [env   "gotc-dev"
        port  3000
        title (str (str/capitalize wfl/the-name) ":")]
    (-> ["%2$s %1$s server starts a server process"
         ""
         "Usage: %1$s server <env> <port>"
         ""
         "Where: <env>  names a deployment environment."
         "       <port> is a port number to listen on."
         ""
         (str/join \space ["Example: %1$s server" env port])]
        (->> (str/join \newline))
        (format wfl/the-name title))))

(def cookie-store
  "A session store for wrap-defaults."
  (cookie/cookie-store
   {:key     (env/getenv "COOKIE_SECRET")
    :readers (merge *data-readers* tc/data-readers)}))

(defn wrap-defaults
  [handler]
  (defaults/wrap-defaults
   handler
   (-> defaults/api-defaults
       (assoc :proxy true)
       (assoc-in [:session :cookie-attrs :same-site] :lax)
       (assoc-in [:session :store] cookie-store))))

(defn wrap-internal-error
  [handler]
  (fn [request]
    (try
      (handler request)
      (catch Throwable t
        (log/error t)))))

;; See https://stackoverflow.com/a/43075132
;;
(defmacro wrap-reload-for-development-only
  "Ensure wrap-reload gets a var instead of a function!"
  [handler]
  `(reload/wrap-reload (var ~handler) {:dirs ["src"]}))

(def app
  "Wrap routes to compile in standard features."
  (-> routes/routes
      wrap-reload-for-development-only
      wrap-params
      wrap-defaults
      wrap-internal-error
      (wrap-json-response {:pretty true})))

(defn ^:private start-workload-manager
  "Update the workload database, then start a `future` to manage the
  state of workflows in the background. Dereference the future to wait
  for the background task to finish (when an error occurs)."
  []
  (letfn [(do-update! [{:keys [id uuid]}]
            (try
              (jdbc/with-db-transaction [tx (postgres/wfl-db-config)]
                (->> (workloads/load-workload-for-id tx id)
                     (workloads/update-workload! tx)))
              (catch PSQLException ex
                (log/warnf "Failed to update workload %s" uuid)
                (log/warn ex))))
          (update-workloads! []
            (log/info "updating workloads")
            (run! do-update!
                  (jdbc/with-db-transaction [tx (postgres/wfl-db-config)]
                    (jdbc/query tx [(str/join " " ["SELECT id,uuid FROM workload"
                                                   "WHERE started IS NOT NULL"
                                                   "AND finished IS NULL"])]))))]
    (log/info "starting workload update loop")
    (update-workloads!)
    (future
      (while true
        (update-workloads!)
        (.sleep TimeUnit/SECONDS 20)))))

(defn ^:private start-webserver
  "Start the jetty webserver asynchronously to serve http requests on the
  specified port. Returns a java.util.concurrent.Future that, when de-
  referenced, blocks until the server ends."
  [port]
  (log/infof "starting jetty webserver on port %s" port)
  (let [server    (jetty/run-jetty app {:port port :join? false})]
    (reify Future
      (cancel [_ _] (throw (UnsupportedOperationException.)))
      (get [_] (.join server))
      (get [_ _ _] (throw (UnsupportedOperationException.)))
      (isCancelled [_] false)
      (isDone [_] (.isStopped server)))))

(defn ^:private await-some
  "Poll the sequence of futures until at least one is done then dereference
  that future. Any exceptions thrown by that future are propagated untouched."
  [& futures]
  (loop []
    (if-let [f (first (filter future-done? futures))]
      (do @f nil)
      (do
        (.sleep TimeUnit/SECONDS 1)
        (recur)))))

(defn run
  "Run child server in ENVIRONMENT on PORT."
  [& args]
  (log/info "Run:" wfl/the-name "server" args)
  (let [port    (util/is-non-negative! (first args))
        manager (start-workload-manager)]
    (await-some manager (start-webserver port))))

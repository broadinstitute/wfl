(ns wfl.server
  "An HTTP API server."
  (:require [clj-time.coerce                :as tc]
            [clojure.string                 :as str]
            [ring.adapter.jetty             :as jetty]
            [ring.middleware.defaults       :as defaults]
            [ring.middleware.json           :refer [wrap-json-response]]
            [ring.middleware.params         :refer [wrap-params]]
            [ring.middleware.reload         :as reload]
            [ring.middleware.session.cookie :as cookie]
            [wfl.api.routes                 :as routes]
            [wfl.api.workloads              :as workloads]
            [wfl.configuration              :as config]
            [wfl.environment                :as env]
            [wfl.jdbc                       :as jdbc]
            [wfl.log                        :as log]
            [wfl.service.postgres           :as postgres]
            [wfl.service.slack              :as slack]
            [wfl.util                       :as util]
            [wfl.wfl                        :as wfl])
  (:import [java.util.concurrent Future]
           [wfl.util UserException]))

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

(defn ^:private cookie-store
  "Create a session store for wrap-defaults."
  []
  (cookie/cookie-store
   {:key     (env/getenv "WFL_COOKIE_SECRET")
    :readers (merge *data-readers* tc/data-readers)}))

(defn wrap-defaults
  [handler]
  (defaults/wrap-defaults
   handler
   (-> defaults/api-defaults
       (assoc :proxy true)
       (assoc-in [:session :cookie-attrs :same-site] :lax)
       (assoc-in [:session :store] (cookie-store)))))

(defn wrap-internal-error
  [handler]
  (fn [request]
    (try
      (handler request)
      (catch Throwable t
        (log/error (str t))))))

;; See https://stackoverflow.com/a/43075132
;;
(defmacro wrap-reload-for-development-only
  "Ensure wrap-reload gets a var instead of a function!"
  [handler]
  `(reload/wrap-reload (var ~handler) {:dirs ["src"]}))

(defn app
  "Wrap routes to compile in standard features."
  []
  (-> routes/routes
      wrap-reload-for-development-only
      wrap-params
      wrap-defaults
      wrap-internal-error
      (wrap-json-response {:pretty true})))

(defn ^:private do-update!
  "Update `workload-record` and notify watchers on UserException."
  [workload-record]
  (try
    (workloads/update-workload! workload-record)
    (catch UserException e
      (log/warning "UserException while updating workload"
                   :workload  (workloads/to-log workload-record)
                   :exception e)
      (slack/notify-watchers workload-record (.getMessage e)))))

(defn ^:private try-update
  "Try to update `workload-record` with backstop."
  [workload-record]
  (try
    (log/info "Updating workload" :workload (workloads/to-log workload-record))
    (do-update! workload-record)
    (catch Throwable t
      (log/error "Failed to update workload"
                 :workload  (workloads/to-log workload-record)
                 :throwable t))))

(defn ^:private update-workloads
  "Update the active workflows in the active workloads."
  []
  (try
    (log/info "Finding workloads to update...")
    (let [query   (str/join \space
                            ["SELECT * FROM workload"
                             "WHERE started IS NOT NULL"
                             "AND finished IS NULL"])
          records (jdbc/with-db-transaction [tx (postgres/wfl-db-config)]
                    (jdbc/query tx query))]
      (run! try-update records))
    (catch Throwable t
      (log/error "Failed to update workloads" :throwable t))))

(defn ^:private start-workload-manager
  "Update the workload database, then start a `future` to manage the
  state of workflows in the background. Dereference the future to wait
  for the background task to finish (when an error occurs)."
  []
  (log/info "Starting workload update loop")
  (update-workloads)
  (future
    (while true
      (update-workloads)
      (util/sleep-seconds 20))))

(defn ^:private start-logging-polling
  "Poll for changes to the log level."
  []
  (letfn [(get-logging-level []
            (jdbc/with-db-transaction [tx (postgres/wfl-db-config)]
              (log/set-active-level
               (config/get-config tx "LOGGING_LEVEL"))))]
    (get-logging-level)
    (future
      (while true
        (get-logging-level)
        (util/sleep-seconds 60)))))

(defn ^:private start-webserver
  "Start the jetty webserver asynchronously to serve http requests on the
  specified port. Returns a java.util.concurrent.Future that, when de-
  referenced, blocks until the server ends."
  [port]
  (log/info "Starting jetty webserver" :port port)
  (let [server (jetty/run-jetty (app) {:port port :join? false})]
    (reify Future
      (cancel      [_ _]   (throw (UnsupportedOperationException.)))
      (get         [_]     (.join server))
      (get         [_ _ _] (throw (UnsupportedOperationException.)))
      (isCancelled [_]     false)
      (isDone      [_]     (.isStopped server)))))

(defn ^:private await-some
  "Poll the sequence of futures until at least one is done then dereference
  that future. Any exceptions thrown by that future are propagated untouched."
  [& futures]
  (loop []
    (if-let [f (first (filter future-done? futures))]
      (do @f nil)
      (do (util/sleep-seconds 20)
          (recur)))))

(defn run
  "Run server in `environment` on `port`."
  [& args]
  (log/info (str/join " " ["Run:" wfl/the-name "server" args]))
  (let [port    (util/is-non-negative! (first args))
        manager (start-workload-manager)
        logger  (start-logging-polling)
        slacker (slack/start-notification-loop)]
    (await-some manager logger slacker (start-webserver port))))

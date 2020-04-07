(ns zero.server
  "An HTTP server with OAuth2 authentication."
  (:require [clojure.pprint :refer [pprint]]
            [clojure.stacktrace :refer [print-throwable]]
            [clojure.string :as str]
            [clj-time.coerce :as tc]
            [ring.adapter.jetty :as jetty]
            [ring.middleware.defaults :as defaults]
            [ring.middleware.json :refer [wrap-json-response]]
            [ring.middleware.oauth2 :as oauth2]
            [ring.middleware.params :refer [wrap-params]]
            [ring.middleware.reload :as reload]
            [ring.middleware.session.cookie :as cookie]
            [zero.environments :as env]
            [zero.util :as util]
            [zero.zero :as zero]
            [zero.api.routes :as routes]
            [zero.api.handlers :as handlers])
  (:import (java.awt Desktop)
           (java.net URI)))

(def description
  "The purpose of this command."
  (let [env   "gotc-dev"
        port  3000
        title (str (str/capitalize zero/the-name) ":")]
    (-> ["%2$s %1$s server starts a server process"
         ""
         "Usage: %1$s server <env> <port>"
         ""
         "Where: <env>  names a deployment environment."
         "       <port> is a port number to listen on."
         ""
         (str/join \space ["Example: %1$s server" env port])]
        (->> (str/join \newline))
        (format zero/the-name title))))

(defn env_variables
  "The process environment variables for ENV."
  [env]
  (let [environment (env env/stuff)
        {:keys [cookie_secret oauth2_client_id oauth2_client_secret]}
        (util/vault-secrets (get-in environment [:server :vault]))]
    {"COOKIE_SECRET"        cookie_secret
     "ENVIRONMENT"          (:name environment)
     "OAUTH2_CLIENT_ID"     oauth2_client_id
     "OAUTH2_CLIENT_SECRET" oauth2_client_secret}))

(def cookie-store
  "A session store for wrap-defaults."
  (cookie/cookie-store
    {:key     (util/getenv "COOKIE_SECRET" "must be 16 bytes")
     :readers (merge *data-readers* tc/data-readers)}))

(defn wrap-oauth2
  [handler]
  (oauth2/wrap-oauth2 handler handlers/oauth2-profiles))

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
        (binding [*err* *out*]
          (print-throwable t))))))

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
      wrap-oauth2
      wrap-defaults
      wrap-internal-error
      (wrap-json-response {:pretty true})))

;; https://stackoverflow.com/a/18509384/7247312
;;
(defn open-in-default-browser
  "Open a new browser (window or tab) viewing the document at this `uri`."
  [uri]
  (if (Desktop/isDesktopSupported)
    (let [desktop (Desktop/getDesktop)]
      (.browse desktop (URI. uri)))
    (let [rt (Runtime/getRuntime)]
      (.exec rt (str "xdg-open " uri)))))

(defn run
  "Run child server in ENVIRONMENT on PORT."
  [& args]
  (pprint (into ["Run:" zero/the-name "server"] args))
  (case (count args)
    2 (let [[environment port] args
            env (zero/throw-or-environment-keyword! environment)
            cmd [(str "./" zero/the-name) "server" port]
            builder (-> cmd ProcessBuilder. .inheritIO)
            environ (.environment builder)]
        (open-in-default-browser "http://localhost:8080")
        (doseq [[k v] (env_variables env)] (.put environ k v))
        (pprint cmd)
        (println (.waitFor (.start builder))))
    1 (let [port {:port (util/is-non-negative! (first args))}]
        (pprint [zero/the-name port])
        (jetty/run-jetty app port))
    (throw (IllegalArgumentException. "Must specify 1 or 2 arguments."))))

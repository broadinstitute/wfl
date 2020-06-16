(ns zero.server
  "An HTTP API server."
  (:require [clojure.pprint :refer [pprint]]
            [clojure.stacktrace :refer [print-throwable]]
            [clojure.string :as str]
            [clj-time.coerce :as tc]
            [ring.adapter.jetty :as jetty]
            [ring.middleware.defaults :as defaults]
            [ring.middleware.json :refer [wrap-json-response]]
            [ring.middleware.params :refer [wrap-params]]
            [ring.middleware.reload :as reload]
            [ring.middleware.session.cookie :as cookie]
            [zero.api.routes :as routes]
            [zero.util :as util]
            [zero.zero :as zero]))

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

(def cookie-store
  "A session store for wrap-defaults."
  (cookie/cookie-store
    {:key     (util/getenv "COOKIE_SECRET" "must be 16 bytes")
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
      wrap-defaults
      wrap-internal-error
      (wrap-json-response {:pretty true})))

(defn run
  "Run child server in ENVIRONMENT on PORT."
  [& args]
  (pprint (into ["Run:" zero/the-name "server"] args))
  (let [port {:port (util/is-non-negative! (first args))}]
    (pprint [zero/the-name port])
    (jetty/run-jetty app port)))

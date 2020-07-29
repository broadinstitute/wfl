(ns zero.server-debug
  "Debug the HTTP server (zero.server)."
  (:require [clojure.pprint :refer [pprint]]
            [clojure.string :as str]
            [clojure.tools.logging :as log]))

(def ignored-keys
  "Ignore these keys when tracing a request or response map."
  [#_:body
   :character-encoding
   :content-length
   :content-type
   :remote-addr
   :scheme
   :server-name
   :server-port
   :ssl-client-cert])

(def ignored-headers
  "Ignore these headers when tracing a request or response."
  ["accept"
   "accept-charset"
   "accept-encoding"
   "accept-language"
   "cache-control"
   "connection"
   "user-agent"])

(defn escape
  "Escape < and > in STRING."
  [string]
  (str/escape string {\< "&lt;" \> "&gt;"}))

(defn format-trace
  "Pretty-print a simplified TRACE with TAG."
  [tag trace]
  (let [{:keys [headers] :as wok} (reduce dissoc trace ignored-keys)
        wokh (assoc wok :headers (reduce dissoc headers ignored-headers))]
    (with-out-str (pprint [tag wokh]))))

(defn wrap-spy
  "TAG request and response traces in HANDLER."
  [handler tag]
  (fn [request]
    (let [incoming (format-trace (str tag " Request:") request)]
      (log/info incoming)
      (let [response (handler request)
            outgoing (format-trace (str tag " Response:") response)]
        (log/info outgoing)
        (update-in response [:body]
                   (fn [body]
                     (str/join \newline
                               ["<pre>" (escape incoming) "</pre>"
                                body
                                "<pre>" (escape outgoing) "</pre>"])))))))

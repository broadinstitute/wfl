(ns zero.start
  (:require [clojure.data.json :as json]
            [clojure.pprint :refer [pprint]]
            [zero.util :as util])
  (:import (java.util UUID)))

(def server
  "https://wfl-dot-broad-gotc-dev.appspot.com"
  "http://localhost:3000")

(defn first-unstarted-workload
  "Use AUTH to return the first unstarted workload at SERVER."
  [auth server]
  (-> server
      (str "/api/v1/workload")
      (->> (vector "curl" "-H" "Content-Type: application/json" "-H" auth)
           (apply util/shell!))
      (json/read-str :key-fn keyword)
      (->> (remove :started))
      first))

(defn start-workload
  "Use AUTH to start WORKLOAD at SERVER."
  [auth server workload]
  (let [uuids (json/write-str [(select-keys workload [:uuid])])
        curl  ["curl" "-H" auth "-H" "Content-Type: application/json"
               "--data" uuids (str server "/api/v1/start")]]
    (json/read-str (apply util/shell! curl) :key-fn keyword)))

(defn get-workload-status
  "Use AUTH to GET the WORKLOAD status at SERVER."
  [auth server workload]
  (let [url (str server "/api/v1/workload?uuid=" (:uuid workload))]
    (json/read-str (util/shell! "curl" "-H" auth url) :key-fn keyword)))

(defn -main
  [& args]
  (let [auth      (str "Authorization: Bearer " (util/create-jwt :gotc-dev))
        unstarted (first-unstarted-workload auth server)
        response  (start-workload auth server unstarted)
        status    (get-workload-status auth server (first response))]
    (pprint [:unstarted unstarted])
    (pprint [:response  response])
    (pprint [:status    status])
    (assert unstarted)
    (assert (:uuid unstarted))
    (assert (= (:uuid (first response)) (:uuid unstarted)))
    (assert (every? :status (:workflows (first status)))))
  (System/exit 0))

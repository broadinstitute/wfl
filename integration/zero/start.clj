(ns zero.start
  (:require [clojure.data.json :as json]
            [clojure.java.io :as io]
            [clojure.pprint :refer [pprint]]
            [clojure.string :as str]
            [zero.util :as util])
  (:import (java.util UUID)))

(def server
  "https://wfl-dot-broad-gotc-dev.appspot.com"
  "http://localhost:3000"
  )

(defn -main
  [& args]
  (let [auth   (str "Authorization: Bearer " (util/create-jwt :gotc-dev))
        curl   ["curl" "-H" auth "-H" "Content-Type: application/json"
                (str server "/api/v1/workload")]
        _ (pprint curl)
        got    (json/read-str (apply util/shell! curl) :key-fn keyword)
        _ (pprint got)
        uuids  (-> got (get 2) (select-keys [:uuid]) vector json/write-str)
        _ (pprint uuids)
        post   ["curl" "-H" auth "-H" "Content-Type: application/json"
                "--data" uuids
                (str server "/api/v1/start")]
        _ (pprint post)
        posted (json/read-str (apply util/shell! post) :key-fn keyword)]
    (pprint posted))
  #_(System/exit 0))

(comment
  (-main)
  )

(ns zero.wgs-test
  (:require [clojure.data.json :as json]
            [clojure.pprint :refer [pprint]]
            [clojure.string :as str]
            [zero.service.cromwell :as cromwell]
            [zero.util :as util])
  (:import (java.util UUID)))

(defn start-wgs-workflow
  "Start up to MAX workflows from INPUT to OUTPUT in ENV Cromwell."
  [env max input output]
  (let [jwt (util/create-jwt :gotc-dev)]
    (-> {:method       :post
         :url          (str "http://localhost:3000/api/v1/wgs")
         :content-type :application/json
         :headers      {"Authorization" (str/join " " ["Bearer" jwt])}
         :body         (json/write-str {:environment env
                                        :max         max
                                        :input_path  input
                                        :output_path output}
                                       :escape-slash false)}
        cromwell/request-json :body :results)))

(defn -main
  "Submit up to MAX workflows from INPUT to OUTPUT in ENV."
  [& args]
  (let [env     :wgs-dev
        max     1
        input   "gs://broad-gotc-test-storage/single_sample/plumbing/bams/2m/"
        output  "gs://broad-gotc-dev-zero-test/wgs-test-output/"
        wait    (partial cromwell/wait-for-workflow-complete env)
        unique  (str/join "/" [output (UUID/randomUUID)])
        ids     (start-wgs-workflow env max input unique)
        results (zipmap ids (map (comp :status wait) ids))]
    (pprint results)
    (System/exit (if (every? #{"Succeeded"} (vals results)) 0 1))))

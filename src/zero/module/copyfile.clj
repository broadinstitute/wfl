(ns zero.module.copyfile
  "A dummy module for smoke testing wfl/cromwell auth."
  (:require [clojure.java.io :as io]
            [clojure.java.jdbc :as jdbc]
            [zero.environments :as env]
            [zero.module.all :as all]
            [zero.service.cromwell :as cromwell]
            [zero.service.postgres :as postgres]
            [zero.util :as util])
  (:import [java.time OffsetDateTime]))

(def pipeline "copyfile")

(def workflow-wdl
  {:release "copyfile-v1.0"
   :top     "wdl/copyfile.wdl"})

(defn- get-environment-for-cromwell
  "Loop up environment from Cromwell URL."
  [url]
  (->> env/stuff
    (filter (fn [[_ values]] (= url ((comp :url :cromwell) values))))
    keys
    first))

(defn- submit-workflow
  "Submit INPUTS to be processed in ENVIRONMENT."
  [environment inputs]
  (cromwell/submit-workflow
    environment
    (io/file (:top workflow-wdl))
    nil
    inputs
    (util/make-options environment)
    {}))

(defn add-workload!
  "Use transaction TX to add the workload described by BODY."
  [tx {:keys [items] :as body}]
  (let [now   (OffsetDateTime/now)
        [uuid table] (all/add-workload-table! tx workflow-wdl body)
        idnow (fn [item id] (-> item (assoc :id id :updated now)))]
    (jdbc/insert-multi! tx table (map idnow items (rest (range))))
    {:uuid uuid}))

(defn start-workload!
  "Use transaction TX to start the WORKLOAD."
  [tx {:keys [cromwell items uuid]}]
  (let [env (get-environment-for-cromwell cromwell)
        now (OffsetDateTime/now)]
    (jdbc/update! tx :workload {:started now} ["uuid = ?" uuid])
    (letfn [(inputs [src dst] {:copyfile.src src :copyfile.dst dst})
            (submit! [{:keys [id uuid src dst]}]
                [id (or uuid (submit-workflow env (inputs src dst)))])
            (update! [tx [id uuid]]
              (when uuid
                (jdbc/update! tx items
                  {:updated now :uuid uuid}
                  ["id = ?" id])))]
      (let [workflow (postgres/get-table tx items)]
        (run! (comp (partial update! tx) submit!) workflow)))))

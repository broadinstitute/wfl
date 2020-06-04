(ns zero.module.copyfile
  "A dummy module for smoke testing wfl/cromwell auth."
  (:require [clojure.java.jdbc :as jdbc]
            [zero.module.all :as all]
            [zero.service.cromwell :as cromwell]
            [zero.service.postgres :as postgres]
            [zero.util :as util]
            [clojure.java.io :as io]
            [zero.environments :as env])
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
  "Submit IN-GS for reprocessing into OUT-GS in ENVIRONMENT."
  [environment out-gs]
  (cromwell/submit-workflow
    environment
    (io/file (:dir (:top workflow-wdl)))
    nil
    []
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
  [tx {:keys [cromwell output items uuid]}]
  (let [env    (get-environment-for-cromwell cromwell)
        output (all/slashify output)
        now    (OffsetDateTime/now)]
    (jdbc/update! tx :workload {:started now} ["uuid = ?" uuid])
    (letfn [(submit! [{:keys [id uuid]}]
              [id (or uuid (submit-workflow env output))])
            (update! [tx [id uuid]]
              (when uuid
                (jdbc/update! tx items
                  {:updated now :uuid uuid}
                  ["id = ?" id])))]
      (let [workflows (postgres/get-table tx items)]
        (run! (comp (partial update! tx) submit!) workflows)))))

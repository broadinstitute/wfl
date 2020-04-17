(ns zero.module.wl
  "Reprocess Whole Genomes in workloads."
  (:require [clojure.java.jdbc :as jdbc]
            [clojure.string :as str]
            [zero.environments :as env]
            [zero.module.all :as all]
            [zero.module.wgs :as wgs]
            [zero.service.postgres :as postgres]
            [zero.service.gcs :as gcs]
            [zero.util :as util])
  (:import [java.time OffsetDateTime]))

(defn add-workload!
  "Add the workload described by BODY to the database DB."
  [db {:keys [load] :as body}]
  (jdbc/with-db-transaction [tx db]
    (let [now          (OffsetDateTime/now)
          [uuid table] (all/add-workload-table! tx wgs/workflow-wdl body)]
      (letfn [(idnow [m id] (-> m (assoc :id id) (assoc :updated now)))]
        (jdbc/insert-multi! tx table (map idnow load (rest (range)))))
      uuid)))

(defn create-workload
  "Remember the workload specified by BODY."
  [body]
  (let [environment (keyword (util/getenv "ENVIRONMENT" "debug"))]
    (->> body
         (add-workload! (postgres/zero-db-config environment))
         (conj ["SELECT * FROM workload WHERE uuid = ?"])
         (jdbc/query (postgres/zero-db-config environment))
         first
         (filter second)
         (into {}))))

(def cromwell->env
  "Map Cromwell URL to a :wgs environment."
  (delay
    (let [envs (select-keys env/stuff [:wgs-dev :wgs-prod :wgs-staging])]
      (zipmap (map (comp :url :cromwell) (vals envs)) (keys envs)))))

(defn start-workload!
  "Start the WORKLOAD in the database DB."
  [db {:keys [cromwell input load output] :as workload}]
  (let [env (@cromwell->env cromwell)
        slashified (all/slashify input)
        now (OffsetDateTime/now)]
    (letfn [(submit! [{:keys [id input_cram uuid] :as _workflow}]
              (if uuid [id uuid]
                  (let [input  (str slashified input_cram)
                        [uuid] (wgs/submit-some-workflows env 1 input output)]
                    [id uuid])))
            (update! [tx [id uuid]]
              [tx [id uuid]]
              (jdbc/update! tx load {:updated now
                                     :uuid    uuid} ["id = ?" id]))]
      (jdbc/with-db-transaction [tx db]
        (->> load
             (format "SELECT * FROM %s")
             (jdbc/query db)
             (map submit!)
             (run! (partial update! tx)))))))

(ns zero.module.wl
  "Reprocess Whole Genomes in workloads."
  (:require [clojure.data.json :as json]
            [clojure.java.jdbc :as jdbc]
            [clojure.string :as str]
            [zero.environments :as env]
            [zero.module.all :as all]
            [zero.module.wgs :as wgs]
            [zero.service.cromwell :as cromwell]
            [zero.service.gcs :as gcs]
            [zero.service.postgres :as postgres]
            [zero.service.gcs :as gcs]
            [zero.util :as util])
  (:import [java.time OffsetDateTime]))

(def cromwell->env
  "Map Cromwell URL to a :wgs environment."
  (delay
    (let [envs (select-keys env/stuff [:wgs-dev :wgs-prod :wgs-staging])]
      (zipmap (map (comp :url :cromwell) (vals envs)) (keys envs)))))

(defn really-update-status!
  "Update the status of LOAD workflows in database DB for ENV."
  [db env load]
  (let [now (OffsetDateTime/now)]
    (letfn [(status! [tx [id uuid]]
              (when uuid
                (jdbc/update! tx load {:status  (cromwell/status env uuid)
                                       :updated now
                                       :uuid    uuid} ["id = ?" id])))]
      (jdbc/with-db-transaction [tx db]
        (->> load
             zero.debug/trace
             (format "SELECT id, uuid FROM %s")
             zero.debug/trace
             (jdbc/query db)
             zero.debug/trace
             (run! (partial status! tx)))))))

(defn update-status!
  "Update statuses in WORKLOAD in the database DB."
  [db {:keys [cromwell load] :as workload}]
  (let [env (@cromwell->env cromwell)]
    (really-update-status! db env load)))

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

(defn skip-workflow?
  [env
   {:keys [input output] :as workload}
   {:keys [input_cram]   :as workflow}]
  (let [in-gs   (str (all/slashify input)  input_cram)
        out-gs  (str (all/slashify output) input_cram)
        done?   (->> out-gs
                     gcs/parse-gs-url
                     (apply gcs/list-objects)
                     util/do-or-nil
                     seq)
        active? (->> {:label wgs/cromwell-label
                      :status ["On Hold" "Running" "Submitted"]}
                     (cromwell/query env)
                     (map :id)
                     (map (partial cromwell/metadata env))
                     (map :submittedFiles)
                     (map :inputs)
                     (map (fn [it] (json/read-str it :key-fn keyword)))
                     (map :ExternalWholeGenomeReprocessing.input_cram)
                     (keep #{in-gs})
                     seq)]
    (or done? active?)))

(defn start-workload!
  "Start the WORKLOAD in the database DB."
  [db {:keys [cromwell input load output] :as workload}]
  (zero.debug/trace workload)
  (let [env    (@cromwell->env cromwell)
        input  (all/slashify input)
        output (all/slashify output)
        now    (OffsetDateTime/now)]
    (letfn [(submit! [{:keys [id input_cram uuid] :as workflow}]
              [id (or uuid (wgs/really-submit-one-workflow
                             env (str input input_cram) output))])
            (update! [tx [id uuid]]
              (jdbc/update! tx load {:updated now
                                     :uuid    uuid} ["id = ?" id]))]
      (util/do-or-nil
        (jdbc/with-db-transaction [tx db]
          (->> load
               zero.debug/trace
               (format "SELECT * FROM %s")
               zero.debug/trace
               (jdbc/query db)
               zero.debug/trace
               (map submit!)
               zero.debug/trace
               (run! (partial update! tx))))))))

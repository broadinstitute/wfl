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

(def pipeline "ExternalWholeGenomeReprocessing")

(def cromwell->env
  "Map Cromwell URL to a :wgs environment."
  (delay
    (let [envs (select-keys env/stuff [:wgs-dev :wgs-prod :wgs-staging])]
      (zipmap (map (comp :url :cromwell) (vals envs)) (keys envs)))))

(defn maybe-update-workflow-status!
  "Use TX to update the status of WORKFLOW in ENV."
  [tx env items {:keys [id uuid] :as _workflow}]
  (letfn [(maybe [m k v] (if v (assoc m k v) m))]
    (when uuid
      (let [now    (OffsetDateTime/now)
            status (util/do-or-nil (cromwell/status env uuid))]
        (jdbc/update! tx items
                      (maybe {:updated now :uuid uuid} :status status)
                      ["id = ?" id])))))

(defn update-workload!
  "Use transaction TX to update WORKLOAD statuses."
  [tx {:keys [cromwell items] :as workload}]
  (let [env (@cromwell->env cromwell)]
    (->> items
         (postgres/get-table tx)
         (run! (partial maybe-update-workflow-status! tx env items)))))

(defn add-workload!
  "Use transaction TX to add the workload described by BODY."
  [tx {:keys [items] :as body}]
  (let [now          (OffsetDateTime/now)
        [uuid table] (all/add-workload-table! tx wgs/workflow-wdl body)]
    (letfn [(idnow [m id] (-> m (assoc :id id) (assoc :updated now)))]
      (jdbc/insert-multi! tx table (map idnow items (rest (range)))))
    {:uuid uuid}))

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
  "True when WORKFLOW in WORKLOAD in ENV is done or active."
  [env
   {:keys [input output] :as workload}
   {:keys [input_cram]   :as workflow}]
  (let [in-gs  (str (all/slashify input)  input_cram)
        out-gs (str (all/slashify output) input_cram)]
    (or (->> out-gs gcs/parse-gs-url
             (apply gcs/list-objects)
             util/do-or-nil seq)        ; done?
        (->> {:label  wgs/cromwell-label
              :status ["On Hold" "Running" "Submitted"]}
             (cromwell/query env)
             (map (comp :ExternalWholeGenomeReprocessing.input_cram
                        (fn [it] (json/read-str it :key-fn keyword))
                        :inputs :submittedFiles
                        (partial cromwell/metadata env)
                        :id))
             (keep #{in-gs})
             seq))))                    ; active?

(defn start-workload!
  "Use transaction TX to start the WORKLOAD."
  [tx {:keys [cromwell input items output uuid] :as workload}]
  (let [env    (@cromwell->env cromwell)
        input  (all/slashify input)
        output (all/slashify output)
        now    (OffsetDateTime/now)]
    (jdbc/update! tx :workload {:started now} ["uuid = ?" uuid])
    (letfn [(maybe [m k v] (if v (assoc m k v) m))
            (submit! [{:keys [id input_cram uuid] :as workflow}]
              [id (or uuid
                      (if (skip-workflow? env workload workflow)
                        util/uuid-nil
                        (wgs/really-submit-one-workflow
                          env (str input input_cram) output)))])
            (update! [tx [id uuid]]
              (when uuid
                (jdbc/update! tx items
                              {:updated now :uuid uuid}
                              ["id = ?" id])))]
      (let [workflows (postgres/get-table tx items)
            ids-uuids (map submit! workflows)]
        (run! (partial update! tx) ids-uuids)))))

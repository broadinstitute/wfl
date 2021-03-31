(ns wfl.tools.snapshots
  (:require [clojure.test         :refer :all]
            [wfl.service.datarepo :as datarepo]
            [wfl.util             :as util]))

(defn unique-snapshot-request
  "Wrap `table` from `dataset` in a snapshot with a unique name for `tdr-profile`."
  [tdr-profile dataset table row-ids]
  (let [columns     (-> (datarepo/all-columns dataset table)
                        (->> (map :name) set)
                        (conj "datarepo_row_id"))]
    (-> (datarepo/make-snapshot-request dataset columns table row-ids)
        (update :name util/randomize)
        (update :profileId (constantly tdr-profile)))))

;; Partition row IDs into batches of 500 to keep TDR happy.
;; Ask Ruchi for the reference to the bug ticket if there's one.
(defn create-snapshots
  "Return snapshot requests of up to 500 row ID
   from `table` in `dataset` for `tdr-profile`.

   Note the dataset row query time range is set to (yesterday, today]
   for testing purposes for now."
  [tdr-profile dataset table]
  (let [today                (util/today)
        yesterday            (util/days-from-today -1)
        unique-request-batch (partial unique-snapshot-request tdr-profile dataset table)]
    (->> (datarepo/query-table-between dataset table [yesterday today] [:datarepo_row_id])
         flatten
         (partition-all 500)
         (map unique-request-batch)
         (map (partial unique-snapshot-request tdr-profile dataset table)))))

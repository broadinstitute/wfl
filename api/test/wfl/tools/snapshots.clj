(ns wfl.tools.snapshots
  (:require [clojure.test         :refer :all]
            [wfl.service.datarepo :as datarepo]
            [wfl.util             :as util]))

(defn unique-snapshot-request
  "Return a snapshot request of `table` from `dataset` for `tdr-profile`."
  [tdr-profile dataset table row-ids]
  (let [columns (-> (datarepo/all-columns dataset (name table))
                    (->> (map :name) set)
                    (conj "datarepo_row_id"))]
    (-> (datarepo/make-snapshot-request dataset columns table row-ids)
        (update :name util/randomize)
        (assoc :profileId tdr-profile))))

;; Partition row IDs into batches of 500 to keep TDR happy.
;; Ask Ruchi for the reference to the bug ticket if there's one.
(defn create-snapshots
  "Return a sequence of snapshot requests for up to 500 of yesterday's
  row IDs from `table` in `dataset` for `tdr-profile`. "
  [tdr-profile dataset table]
  (->> [:datarepo_row_id]
       (datarepo/query-table-between dataset table (util/days -1))
       flatten
       (partition-all 500)
       (map (partial unique-snapshot-request tdr-profile dataset table))))

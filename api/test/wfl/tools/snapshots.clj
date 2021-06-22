(ns wfl.tools.snapshots
  (:require [wfl.service.datarepo :as datarepo]
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

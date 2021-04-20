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

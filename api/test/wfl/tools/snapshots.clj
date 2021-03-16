(ns wfl.tools.snapshots
  (:require [clojure.string :as str]
            [clojure.test :refer :all]
            [wfl.service.google.bigquery :as bigquery]
            [wfl.service.datarepo :as datarepo]
            [wfl.util          :as util])
  (:import (java.util UUID)))

(defn ^:private legalize-tdr-columns
  "Legalize TDR columns by stripping out columns that are Array[File] types, as
   TDR does not support them yet."
  [cols]
  (letfn [(is-fileref-array? [{:keys [datatype array_of]}]
            (and (= datatype "fileref") array_of))]
    (remove is-fileref-array? cols)))

(defn unique-snapshot-request
  "Create a snapshot request for uniquely-named snapshot defined by a
    `dataset` map, `table` name and `tdr-profile`."
  [tdr-profile dataset table row-ids]
  (let [columns     (-> (datarepo/all-columns dataset table)
                        legalize-tdr-columns
                        (#(map :name %))
                        set
                        (conj "datarepo_row_id")
                        vec)
        the-request (->> row-ids
                         (datarepo/compose-snapshot-request dataset columns table))]
    (-> the-request
        (update :name #(str % (-> (UUID/randomUUID) (str/replace "-" ""))))
        (update :profileId (constantly tdr-profile)))))

;; TDR dis-encourages snapshotting by `row-id`s with a large number
;; of rows in the request due to performance issues. Ruchi says it's
;; probably fine to start with snapshotting by 500-row sized batches
;; of rows. As TDR scales up, this number will likely to increase.
(defn create-snapshots
  "Create uniquely-named snapshots in TDR with `tdr-profile`, `dataset`
   map and `table` name.

   Note the dataset row query time range is set to (yesterday, today]
   for testing purposes for now."
  [tdr-profile dataset table]
  (let [{:keys [dataProject]} dataset
        table                table
        today                (util/datetime->str (util/now))
        yesterday            (util/datetime->str (util/days-from-now -1))
        row-ids (->> (datarepo/compose-snapshot-query dataset table yesterday today)
                     (bigquery/query-sync dataProject)
                     (bigquery/flatten-rows))
        unique-request-batch (partial unique-snapshot-request tdr-profile dataset table)]
    (->> (partition-all 500 row-ids)
         (map unique-request-batch)
         (map datarepo/create-snapshot))))

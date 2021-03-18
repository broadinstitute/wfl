(ns wfl.tools.snapshots
  (:require [clojure.string :as str]
            [clojure.test :refer :all]
            [wfl.service.google.bigquery :as bigquery]
            [wfl.service.datarepo :as datarepo]
            [wfl.util          :as util])
  (:import (java.util UUID)))

;; See https://broadworkbench.atlassian.net/browse/DR-1696
(defn ^:private legalize-tdr-columns
  "Legalize TDR columns by stripping out columns that are Array[File] types, as
   TDR does not support them yet."
  [cols]
  (letfn [(is-fileref-array? [{:keys [datatype array_of]}]
            (and (= datatype "fileref") array_of))]
    (remove is-fileref-array? cols)))

(defn unique-snapshot-request
  "Wrap `table` from `dataset` in a snapshot with a unique name for `tdr-profile`."
  [tdr-profile dataset table row-ids]
  (let [columns     (-> (datarepo/all-columns dataset table)
                        legalize-tdr-columns
                        (->> (map :name) set)
                        (conj "datarepo_row_id"))]
    (-> (datarepo/make-snapshot-request dataset columns table row-ids)
        (update :name #(str % (-> (UUID/randomUUID) (str/replace "-" ""))))
        (update :profileId (constantly tdr-profile)))))

;; Partition row IDs into batches of 500 to keep TDR happy.
;; Ask Ruchi for the reference to the bug ticket if there's one.
(defn create-snapshots
  "Return snapshot requests of up to 500 row ID
   from `table` in `dataset` for `tdr-profile`.

   Note the dataset row query time range is set to (yesterday, today]
   for testing purposes for now."
  [tdr-profile dataset table]
  (let [{:keys [dataProject]} dataset
        table                table
        today                (util/today)
        yesterday            (util/days-from-today -1)
        row-ids (->> (datarepo/make-snapshot-query dataset table yesterday today)
                     (bigquery/query-sync dataProject)
                     flatten)
        unique-request-batch (partial unique-snapshot-request tdr-profile dataset table)]
    (->> (datarepo/make-snapshot-query dataset table yesterday today)
         (bigquery/query-sync dataProject)
         flatten
         (partition-all 500)
         (map unique-request-batch)
         (map (partial unique-snapshot-request tdr-profile dataset table)))))

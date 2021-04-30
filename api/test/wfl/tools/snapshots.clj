(ns wfl.tools.snapshots
  "Manage snapshots of Data Repo tables."
  (:require [clojure.test         :refer :all]
            [wfl.environment      :as environment]
            [wfl.service.datarepo :as datarepo]
            [wfl.util             :as util]))

(defn unique-snapshot-request
  "Return a snapshot request of `table` from `dataset` for `tdr-profile`."
  [tdr-profile dataset table row-ids]
  (let [columns (-> (datarepo/all-columns dataset table)
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

(comment
  (let [profile (environment/getenv "WFL_TDR_DEFAULT_PROFILE")
        dataset (datarepo/dataset "ff6e2b40-6497-4340-8947-2f52a658f561")
        table   "flowcell"]
    (-> (datarepo/query-table-between
         dataset table "updated"
         ["2021-03-29 00:00:00" "2021-03-31 00:00:00"]
         #_(util/days 40)
         [:datarepo_row_id])
        :rows
        flatten
        (->> (unique-snapshot-request profile dataset table))))
  {:contents
   [{:datasetName "sarscov2_illumina_full_inputs",
     :mode "byRowId",
     :rowIdSpec
     {:tables
      [{:columns
        #{"biosample_map" "datarepo_row_id" "flowcell_tgz"
          "instrument_model" "flowcell_id" "title" "authors_sbt"
          "updated" "extra" "samplesheets" "sample_rename_map"},
        :rowIds ["da8ee8df-4a89-40d2-810a-792ab0030087"],
        :tableName "flowcell"}]}}],
   :description
   "initial flowcell values for the sarscov2_illumina_full COVID-19 surveillance pipeline",
   :name
   "sarscov2_illumina_full_inputs3d9770605ebe403283a3b39447c0fd01",
   :profileId "390e7a85-d47f-4531-b612-165fc977d3bd"}
  )

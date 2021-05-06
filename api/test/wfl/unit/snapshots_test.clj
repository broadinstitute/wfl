(ns wfl.unit.snapshots-test
  (:require [clojure.test         :refer :all]
            [clojure.data         :as data]
            [wfl.service.datarepo :as datarepo]
            [wfl.tools.resources  :as resources]
            [wfl.tools.snapshots  :as snapshots]
            [wfl.util             :as util]))

(deftest snapshot-table-rows
  (testing "snapshot rows in a dataset table"
    (let [profile (wfl.environment/getenv "WFL_TDR_DEFAULT_PROFILE")
          dataset (datarepo/dataset "ff6e2b40-6497-4340-8947-2f52a658f561")
          table   :flowcell]
      (letfn [(unname [m] (dissoc m :name))
              (snap   [interval] (-> (datarepo/query-table-between
                                      dataset table :updated
                                      (util/days 40)
                                      [:datarepo_row_id])
                                     :rows flatten
                                     (->> (snapshots/unique-snapshot-request
                                           profile dataset table))))]
        (let [days40     (snap (util/days 40))
              march      (snap ["2021-03-29 00:00:00" "2021-03-31 00:00:00"])
              [_ _ both] (data/diff days40 march)]
          (is (apply = (cons both (map unname [days40 march])))))))))

(comment (snapshot-table-rows))

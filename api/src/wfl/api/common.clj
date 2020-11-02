(ns wfl.api.common
  "API support for workflow content being partially common across a workload."
  (:require [wfl.service.postgres :as postgres]
            [wfl.jdbc :as jdbc]
            [wfl.util :as util]
            [clojure.data.json :as json]))

(defn store-common
  [tx workload-id content]
  (jdbc/insert! tx :common_within_workload
                (-> content
                    (->>
                      (map (fn [[k v]] [k (json/write-str v)]))
                      (into {}))
                    (assoc :workload_id workload-id))))

(defn ^:private unnilify
  [m]
  (if (map? m)
    (not-empty (into {} (filter second (map (fn [[k v]] [k (unnilify v)]) m))))
    m))

(defn ^:private read-common
  [tx workload-id]
  (let [common (jdbc/query tx ["SELECT * FROM common_within_workload WHERE workload_id = ?" workload-id])]
    (->> (dissoc (first common) :workload_id)
         (map (fn [[k v]] [k (util/parse-json v)]))
         (into {})
         unnilify)))

(defn load-common-into-workload
  [tx workload]
  (if-let [content (read-common tx (:id workload))]
    (-> workload
        (assoc :common content)
        (update :workflows #(map (partial util/deep-merge content) %)))
    workload))



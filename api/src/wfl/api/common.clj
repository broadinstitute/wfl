(ns wfl.api.common
  "API support for workflow content being partially common across a workload."
  (:require [wfl.jdbc :as jdbc]
            [wfl.util :as util]
            [clojure.data.json :as json]))

(defn store-common
  "Store CONTENT to be common across the entire workload."
  [tx workload-id content]
  (jdbc/insert! tx :common_within_workload
                (-> content
                    (->>
                      (map (fn [[k v]] [k (json/write-str v)]))
                      (into {}))
                    (assoc :workload_id workload-id))))

(defn ^:private unnilify
  "A recursive no-keys-for-nil-or-empty-values helper."
  [m]
  (if (map? m)
    (not-empty (into {} (filter second (map (fn [[k v]] [k (unnilify v)]) m))))
    m))

(defn ^:private read-common
  "The opposite of [[store-common]], simply reads the content back out, [[unnilify]]ed."
  [tx workload-id]
  (let [common (jdbc/query tx ["SELECT * FROM common_within_workload WHERE workload_id = ?" workload-id])]
    (->> (dissoc (first common) :workload_id)
         (map (fn [[k v]] [k (util/parse-json v)]))
         (into {})
         unnilify)))

(defn load-common-into-workload
  "Put any common content back into WORKLOAD, making it the baseline for each workflow item."
  [tx workload]
  (if-let [content (read-common tx (:id workload))]
    (-> workload
        (assoc :common content)
        (update :workflows #(map (partial util/deep-merge content) %)))
    workload))



(ns wfl.tools.datasets
  (:require [clojure.string :as str]
            [clojure.data.json :as json]
            [wfl.environment :as env]
            [wfl.service.datarepo :as datarepo]
            [wfl.service.google.storage :as storage])
  (:import (java.util UUID)))

(defn unique-dataset-request
  ""
  [dataset-basename]
  (let [profile (env/getenv "WFL_TERRA_DATA_REPO_DEFAULT_PROFILE")]
    (-> (str "test/resources/datasets/" dataset-basename)
        slurp
        json/read-str
      ;; give it a unique name to avoid collisions with other tests
        (update "name" #(str % (-> (UUID/randomUUID) (str/replace "-" ""))))
        (update "defaultProfileId" (constantly profile)))))

(defn ^:private ensure-tdr-can-read [bkt-obj-pairs]
  (let [logged #(fn [& args] (wfl.util/do-or-nil (apply % args)))
        sa      (env/getenv "WFL_TERRA_DATA_REPO_SA")]
    (run! (logged #(storage/add-storage-object-viewer sa %))
          (into #{} (map first bkt-obj-pairs)))))

;; TDR limits size of bulk ingest request to 1000 files. Muscles says
;; requests at this limit are "probably fine" and testing with large
;; numbers of files (and size thereof) supports this. If this is becomes
;; a serious bottleneck, suggest we benchmark and adjust the `split-at`
;; value input accordingly.
(defn ingest-files
  ""
  [prefix dataset-id profile-id files]
  (letfn [(target-name  [obj]     (str/join "/" ["" prefix obj]))
          (mk-url       [bkt obj] (format "gs://%s/%s" bkt obj))
          (ingest-batch [batch]
            (->> (for [[bkt obj] batch] [(mk-url bkt obj) (target-name obj)])
                 (datarepo/bulk-ingest dataset-id profile-id)))]
    (let [bkt-obj-pairs (map storage/parse-gs-url files)]
      (ensure-tdr-can-read bkt-obj-pairs)
      (->> (split-at 1000 bkt-obj-pairs)
           (mapv ingest-batch)
           (mapcat #(-> % datarepo/poll-job :loadFileResults))
           (map #(mapv % [:sourcePath :fileId]))
           (into {})))))

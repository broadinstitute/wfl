(ns wfl.tools.datasets
  (:require [clojure.string :as str]
            [clojure.data.json :as json]
            [wfl.service.datarepo :as datarepo]
            [wfl.service.google.storage :as storage])
  (:import (java.util UUID)))

(defn unique-dataset-request
  "Create a dataset request for a uniquely-named dataset defined by the json
   file `dataset-basename` with the specified `tdr-profile`."
  [tdr-profile dataset-basename]
  (-> (str "test/resources/datasets/" dataset-basename)
      slurp
      json/read-str
      ;; give it a unique name to avoid collisions with other tests
      (update "name" #(str % (-> (UUID/randomUUID) (str/replace "-" ""))))
      (update "defaultProfileId" (constantly tdr-profile))))

;; TDR limits size of bulk ingest request to 1000 files. Muscles says
;; requests at this limit are "probably fine" and testing with large
;; numbers of files (and size thereof) supports this. If this is becomes
;; a serious bottleneck, suggest we benchmark and adjust the `split-at`
;; value input accordingly.
(defn ingest-files
  "Ingest `files` into a TDR dataset with `dataset-id` under `prefix` and
   return a map from url to ingested file-id."
  [tdr-profile dataset-id prefix files]
  (letfn [(target-name [url]
            (let [[_ obj] (storage/parse-gs-url url)]
              (str/join "/" ["" prefix obj])))
          (ingest-batch [batch]
            (->> (for [url batch] [url (target-name url)])
                 (datarepo/bulk-ingest dataset-id tdr-profile)))]
    (->> (split-at 1000 files)
         (mapv ingest-batch)
         (mapcat #(-> % datarepo/poll-job :loadFileResults))
         (map #(mapv % [:sourcePath :fileId]))
         (into {}))))

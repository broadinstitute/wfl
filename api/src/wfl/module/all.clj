(ns wfl.module.all
  "Some utilities shared across module namespaces."
  (:require [clojure.string :as str]
            [wfl.jdbc :as jdbc]
            [wfl.service.google.storage :as gcs]
            [wfl.util :as util]
            [wfl.wfl :as wfl])
  (:import [java.util UUID]))

(defn throw-when-output-exists-already!
  "Throw when GS-OUTPUT-URL output exists already."
  [gs-output-url]
  (when (first (gcs/list-objects gs-output-url))
    (throw
     (IllegalArgumentException.
      (format "%s: output already exists: %s"
              wfl/the-name gs-output-url)))))

(defn bam-or-cram?
  "Nil or a vector with root of PATH and the matching suffix."
  [path]
  (or (let [bam (util/unsuffix path ".bam")]
        (when (not= bam path)
          [:input_bam bam ".bam"]))
      (let [cram (util/unsuffix path ".cram")]
        (when (not= cram path)
          [:input_cram cram ".cram"]))))

(defn readable!
  "Throw or return the bucket for GS-URL when it is readable."
  [gs-url]
  (let [[result prefix] (gcs/parse-gs-url gs-url)]
    (letfn [(ok? [bucket prefix]
              (prn (format "%s: reading: %s" wfl/the-name gs-url))
              (util/do-or-nil (gcs/list-objects bucket prefix)))]
      (when-not (ok? result prefix)
        (throw (IllegalArgumentException.
                (format "%s must be readable" gs-url)))))
    result))

(defn processed-crams
  "Root object names of CRAMs in OUT-GS."
  [out-gs]
  (let [[bucket prefix] (gcs/parse-gs-url out-gs)]
    (letfn [(cram? [{:keys [name]}]
              (when (str/ends-with? name ".cram")
                (-> name
                    (util/unsuffix ".cram")
                    (subs (count prefix)))))]
      (->> (gcs/list-objects bucket prefix)
           (keep cram?)
           set))))

(defn count-files
  "Count the BAM or CRAM files in IN-GS, and the BAM or CRAM files
  in OUT-GS."
  [in-gs out-gs]
  (letfn [(crams [gs-url]
            (prn (format "%s: reading: %s" wfl/the-name gs-url))
            [gs-url (->> (gcs/list-objects gs-url)
                         (keep (comp bam-or-cram? :name))
                         count)])]
    (let [gs-urls (into (array-map) (map crams [in-gs out-gs]))
          remaining (apply - (map gs-urls [in-gs out-gs]))]
      (into gs-urls [[:remaining remaining]]))))

(defn add-workload-table!
  "Return ID and TABLE for _WORKFLOW-WDL in BODY under transaction TX."
  [tx {:keys [release path] :as _workflow-wdl} body]
  (let [{:keys [creator executor input output pipeline project]} body
        {:keys [commit version]} (wfl/get-the-version)
        [{:keys [id]}]
        (jdbc/insert! tx :workload {:commit   commit
                                    :creator  creator
                                    :executor executor
                                    :input    input
                                    :output   output
                                    :project  project
                                    :release  release
                                    :uuid     (UUID/randomUUID)
                                    :version  version
                                    :wdl      path})
        table (format "%s_%09d" pipeline id)
        work (format "CREATE TABLE %s OF %s (PRIMARY KEY (id))"
                     table pipeline)]
    (jdbc/update! tx :workload {:items table} ["id = ?" id])
    (jdbc/execute! tx ["UPDATE workload SET pipeline = ?::pipeline WHERE id = ?" pipeline id])
    (jdbc/db-do-commands tx [work])
    [id table]))

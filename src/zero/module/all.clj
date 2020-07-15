(ns zero.module.all
  "Some utilities shared across module namespaces."
  (:require [clojure.java.jdbc :as jdbc]
            [clojure.pprint :refer [pprint]]
            [clojure.string :as str]
            [zero.service.cromwell :as cromwell]
            [zero.service.gcs :as gcs]
            [zero.environments :as env]
            [zero.util :as util]
            [zero.zero :as zero])
  (:import [java.util UUID]))

(defn throw-when-output-exists-already!
  "Throw when GS-OUTPUT-URL output exists already."
  [gs-output-url]
  (let [[bucket object] (gcs/parse-gs-url gs-output-url)]
    (when (first (gcs/list-objects bucket object))
      (throw
       (IllegalArgumentException.
        (format "%s: output already exists: %s"
                zero/the-name gs-output-url))))))

(defn bam-or-cram?
  "Nil or a vector with root of PATH and the matching suffix."
  [path]
  (or (let [bam (util/unsuffix path ".bam")]
        (when (not= bam path)
          [:input_bam  bam  ".bam"]))
      (let [cram (util/unsuffix path ".cram")]
        (when (not= cram path)
          [:input_cram cram ".cram"]))))

(defn readable!
  "Throw or return the bucket for GS-URL when it is readable."
  [gs-url]
  (let [[result prefix] (gcs/parse-gs-url gs-url)]
    (letfn [(ok? [bucket prefix]
              (prn (format "%s: reading: %s" zero/the-name gs-url))
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
            (prn (format "%s: reading: %s" zero/the-name gs-url))
            [gs-url (->> gs-url gcs/parse-gs-url
                         (apply gcs/list-objects)
                         (keep (comp bam-or-cram? :name))
                         count)])]
    (let [gs-urls   (into (array-map) (map crams [in-gs out-gs]))
          remaining (apply - (map gs-urls [in-gs out-gs]))]
      (into gs-urls [[:remaining remaining]]))))

(defn workflow-status
  "Get the status of workflows in ENVIRONMENT."
  [environment label]
  (prn (format "%s: querying %s Cromwell: %s"
               zero/the-name environment (cromwell/url environment)))
  (cromwell/status-counts environment {:label label}))

(defn report-status
  "Report workflow statuses in ENVIRONMENT and file counts."
  [label environment in-gs out-gs]
  (pprint (workflow-status environment label))
  (pprint (count-files in-gs out-gs)))

(defn add-workload-table!
  "Return UUID and TABLE for _WORKFLOW-WDL in BODY under transaction TX."
  [tx {:keys [release top] :as _workflow-wdl} body]
  (let [{:keys [creator cromwell input output pipeline project]} body
        {:keys [commit version]} (zero/get-the-version)
        [{:keys [id uuid]}]
        (jdbc/insert! tx :workload {:commit   commit
                                    :creator  creator
                                    :cromwell cromwell
                                    :input    input
                                    :output   output
                                    :project  project
                                    :release  release
                                    :uuid     (UUID/randomUUID)
                                    :version  version
                                    :wdl      top})
        table (format "%s_%09d" pipeline id)
        work  (format "CREATE TABLE %s OF %s (PRIMARY KEY (id))"
                      table pipeline)]
    (jdbc/update! tx :workload {:items table} ["id = ?" id])
    (jdbc/execute! tx ["UPDATE workload SET pipeline = '?'::pipeline WHERE id = ?" pipeline id])
    (jdbc/db-do-commands tx [work])
    [uuid table]))

(defn slashify
  "Ensure URL ends in a slash /."
  [url]
  (if (str/ends-with? url "/")
    url
    (str url "/")))

(defn cromwell-environments
  "Keywords from the set of ENVIRONMENTS with Cromwell URL."
  ([url]
   (->> env/stuff
        (filter #(-> % second :cromwell :url #{url}))
        keys))
  ([environments url]
   (->> url cromwell-environments
        (filter environments))))

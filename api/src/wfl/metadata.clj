(ns wfl.metadata
  "Collect metadata for successful workflows."
  (:require [clojure.data.json :as json]
            [clojure.string :as str]
            [clojure.tools.logging :as log]
            [wfl.service.cromwell :as cromwell]
            [wfl.service.gcs :as gcs]
            [wfl.wfl :as wfl]))

(defn successes
  "Successful top-level workflows with WF-NAME from Cromwell in ENV."
  [env wf-name]
  (cromwell/query env {:name wf-name
                       :status "Succeeded"
                       :includeSubworkflows false}))

(defn get-metadata
  "Get workflow metadata given a Cromwell ENV and query result dict."
  [env md]
  (let [id (:id md)]
    (cromwell/metadata env id)))

(defn record-metadata
  "Write workflow METADATA to a file and upload to an output directory.
  This directory is either the same as the main workflow output
  (specified by the outputs KEY) or the :destination_cloud_path."
  [output-key metadata]
  (if-let [output-file-url (output-key (:outputs metadata))]
    (let [end-time (:end metadata)
          inputs (:inputs metadata)
          url-parts (str/split output-file-url #"/")
          output-file (last url-parts)
          output-dir (clojure.string/join "/" (pop url-parts))
          metadata-file (str output-file "_" end-time ".metadata.json")]
      (log/info "Saving metadata to" metadata-file)
      (spit metadata-file (json/write-str metadata :escape-slash false))
      (if (:destination_cloud_path inputs)
        (gcs/upload-file
         metadata-file
         (str (:destination_cloud_path inputs) "/" metadata-file))
        (gcs/upload-file metadata-file (str output-dir "/" metadata-file))))
    (str "Skipping workflow " (:id metadata)
         " - no valid output destination\n")))

(defn get-workflow-metadata-by-output
  "Record metadata for all successful workflows in a Cromwell ENV given
  a WF-NAME and an OUTPUT-KEY to indicate which output file the
  metadata should be saved next to."
  [env wf-name output-key]
  (log/info (->> (successes env wf-name)
              (map (partial get-metadata env))
              (map (partial record-metadata (keyword output-key))))))

(def description
  "Describe this command."
  (str/join
   \newline
   ["Usage: wfl metadata <env> <wf-name> <output-key>"
    ""
    "Where: <env> is an environment"
    "       <wf-name> is the name of a workflow run in Cromwell"
    "       <output-key> is a key in the workflow output metadata"
    "                    (where the metadata file will be written)"
    ""
    (str "Example: wfl metadata pharma5 WhiteAlbumExomeReprocessing"
         "WhiteAlbumExomeReprocessing.output_cram")]))

;; TODO: Add destination path key as optional parameter?
;;
(defn run
  "Record metadata for successful workflows specified by ARGS."
  [& args]
  (try
    (let [env (wfl/throw-or-environment-keyword! (first args))]
      (apply (case (count args)
               3 get-workflow-metadata-by-output
               (throw (IllegalArgumentException. "Must specify 3 arguments.")))
             env (rest args)))
    (catch Exception x
      (log/error x)
      (log/debug description)
      (throw x))))

(comment
  (run :hca-int "TestCopyFiles" :TestCopyFiles.hello.response))

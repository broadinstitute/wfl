(ns zero.wdl
  "Manage WDL files for Cromwell workflows."
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [zero.util :as util]
            [zero.zero :as zero])
  (:import [java.io File FileNotFoundException]
           [java.util UUID]))

(defn workflow-name
  "The name of a workflow defined by the wdl file."
  [workflow-wdl]
  (-> workflow-wdl (str/split #"/") last (util/unsuffix ".wdl")))

(defn imports
  "Canonical files directly imported by WDL file."
  [^File wdl]
  (let [re  #"^( *import *\" *)([^\"][^\"]*) *\" *.*$"
        dir (.getParentFile wdl)]
    (letfn [(follow [line]
              (let [[_ _ import :as match?] (re-matches re line)]
                (when match? (.getCanonicalFile (io/file dir import)))))]
      (with-open [in (io/reader (io/file wdl))]
        (doall (keep follow (line-seq in)))))))

(defn collect-files
  "The TOP file followed by direct and indirect imports by TOP."
  [^File top]
  (let [canonical (.getCanonicalFile (io/file top))]
    (loop [sofar #{} wdls [canonical]]
      (if-let [wdl (first wdls)]
        (let [imports (imports wdl)
              more    (remove sofar imports)]
          (recur (into sofar imports) (into (rest wdls) more)))
        (into [canonical] sofar)))))

(defn cromwellify-file
  "Throw or write file named WDL with the import statments fixed into
  DIRECTORY and return the result."
  [^File directory wdl]
  (let [re #"^( *import \").*/(.*)(\".*)$"
        result (io/file directory (.getName (io/file wdl)))]
    (when (.exists result)
      (throw (IllegalArgumentException.
              (format "%s: Two WDL files have the same leaf name: %s"
                      zero/the-name result))))
    (io/make-parents result)
    (with-open [out (io/writer result)
                in  (io/reader (io/file wdl))]
      (binding [*out* out]
        (doseq [line (line-seq in)]
          (let [[_ import leaf suffix :as match?] (re-matches re line)]
            (println (if match? (str import leaf suffix) line))))))
    result))

(defn cromwellify
  "Prepare for Cromwell the workflow in the TOP WDL file by finding the
  imported WDL files, flattening the import directory structure, and
  zipping up the imports.

  Return [directory wf dependencies], where DIRECTORY is a new
  directory File containing the prepared source WDL files, WF is the
  prepared string contents of the ROOT-WF workflow WDL file, and
  dependencies is a .zip File containing the imported WDLs.

  Call (util/delete-tree directory) at some point."
  [top]
  (try
    (let [[root-wf & imports] (collect-files top)
          uuid                (UUID/randomUUID)
          directory           (io/file (str "CROMWELLIFY-" uuid))
          zip                 (io/file directory (str uuid ".zip"))]
      (doseq [wdl imports] (cromwellify-file directory wdl))
      (letfn [(dependency? [f] (let [fname (.getName f)]
                                 (and (not= (.getName root-wf) fname)
                                      (str/ends-with? fname ".wdl"))))]
        [directory
         (cromwellify-file directory root-wf)
         (when (seq imports) (->> directory
                                  file-seq
                                  (filter dependency?)
                                  (apply util/zip-files zip)))]))
    (catch FileNotFoundException x
      (binding [*out* *err*]
        (println (format "%s: WARNING: %s" zero/the-name (.getMessage x)))))))

(defn cromwellify-wdl-resources-hack
  "Return map of suffixes to paths to JAR resources for WDL file."
  [wdl]
  (let [suffix [".wdl" ".zip"]
        wf (workflow-name wdl)]
    (zipmap suffix (map (partial str zero/the-name "/" wf) suffix))))

;; HACK: The (or ...) gets the resource name into the exception info.
;;
(defn hack-unpack-resources-hack
  "Avoid 'URI is not hierarchical' reading resources from jar for WDL."
  [wdl]
  (let [path (cromwellify-wdl-resources-hack wdl)
        dir (io/file (System/getProperty "java.io.tmpdir"))]
    (doseq [resource (vals path)]
      (let [destination (io/file dir resource)]
        (io/make-parents destination)
        (with-open [in (io/input-stream (or (io/resource resource) resource))]
          (io/copy in destination))))
    (assoc path :dir dir)))

(ns wfl.tools.workflows
  (:require [clojure.string :as str]
            [wfl.mime-type  :as mime-type]
            [wfl.util       :as util])
  (:import [java.time.format DateTimeFormatter]))

(defn convert-to-bulk
  "Convert fileref column to BulkLoadFileModel for TDR"
  [value bucket]
  (let [basename (util/basename value)]
    {:description basename
     :mimeType    (mime-type/ext-mime-type value)
     :sourcePath  value
     :targetPath  (str/join "/" [bucket basename])}))

(defn tdr-now
  "Now in the Data Repository's date time format."
  []
  (.format (util/utc-now)
           (DateTimeFormatter/ofPattern "YYYY-MM-dd'T'HH:mm:ss")))

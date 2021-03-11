(ns build
  (:require [clojure.data.xml      :as xml]
            [clojure.java.io       :as io]
            [clojure.pprint        :refer [pprint]]
            [clojure.string        :as str]
            [wfl.module.aou        :as aou]
            [wfl.module.wgs        :as wgs]
            [wfl.module.xx         :as xx]
            [wfl.module.sg         :as sg]
            [wfl.service.firecloud :as firecloud]
            [wfl.util              :as util])
  (:import (java.time Instant)))

;; Java chokes on colons in the version string of the jarfile manifest.
;; And GAE chokes on everything else.
;;
(def the-version
  "A map of version information."
  (letfn [(make-name->release [{:keys [release path]}]
            [(util/basename path) release])]
    (let [built     (str (Instant/now))
          commit    (util/shell! "git" "rev-parse" "HEAD")
          committed (->> commit
                         (util/shell! "git" "show" "-s" "--format=%cI")
                         Instant/parse
                         str)]
      (into
        {:version   (or (System/getenv "WFL_VERSION") "devel")
         :commit    commit
         :committed committed
         :built     built
         :user      (or (System/getenv "USER") "wfl")}
        (map make-name->release [aou/workflow-wdl
                                 sg/workflow-wdl
                                 wgs/workflow-wdl
                                 xx/workflow-wdl])))))

(defn write-the-version-file
  "Write VERSION.edn into the RESOURCES directory."
  [resources version]
  (let [file (io/file resources "version.edn")]
    (io/make-parents file)
    (with-open [out (io/writer file)]
      (binding [*out* out]
        (pprint version)))))

(xml/alias-uri 'pom "http://maven.apache.org/POM/4.0.0")

(def the-pom
  "The POM elements that override the uberdeps/uberjar defaults."
  (let [artifactId 'org.broadinstitute/wfl]
    #::pom{:artifactId  (name artifactId)
           :description "WFL manages workflows."
           :groupId     (namespace artifactId)
           :name        "WorkFlow Launcher"
           :url         "https://github.com/broadinstitute/wfl.git"
           :version     (:version the-version)}))

(defn update-the-pom
  "Update the Project Object Model (pom.xml) file for this program."
  [{:keys [in out]}]
  (let [override? (set (keys the-pom))]
    (io/make-parents out)
    (letfn [(override [{:keys [tag] :as element}]
              (if (override? tag)
                (assoc element :content (vector (the-pom tag)))
                element))]
      (-> in io/file io/reader xml/parse
          (update :content (partial map override))
          xml/emit-str
          (->> (spit out)))))
  (System/exit 0))

(defn ^:private write-workflow-description [resources wdl]
  (let [workflow (util/remove-extension (util/basename wdl))
        file     (io/file (str resources "/workflows/" workflow ".edn"))]
    (io/make-parents file)
    (with-open [out (io/writer file)]
      (binding [*out* out]
        (-> (slurp wdl) firecloud/describe-workflow pprint)))))

(defn ^:private find-wdls []
  (mapcat
    (fn [folder] (->> (file-seq (io/file folder))
                      (map #(.getCanonicalPath %))
                      (filter #(= (util/extension %) "wdl"))))
    ["resources" "test/resources"]))

(defn prebuild
  "Stage any needed resources on the class path."
  [_opts]
  (let [derived (str/join "/" [".." "derived" "api"])]
    (pprint the-version)
    (write-the-version-file (io/file derived "resources" "wfl") the-version)
    (run!
      #(write-workflow-description (io/file derived "test" "resources") %)
      (find-wdls))))

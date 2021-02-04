(ns build
  "Build support originating in build.boot."
  (:require [clojure.data.xml :as xml]
            [clojure.java.io :as io]
            [clojure.pprint :refer [pprint]]
            [clojure.string :as str]
            [wfl.module.aou :as aou]
            [wfl.module.wgs :as wgs]
            [wfl.module.xx :as xx]
            [wfl.module.sg :as sg]
            [wfl.util :as util])
  (:import [java.time OffsetDateTime]
           [java.time.temporal ChronoUnit]))

;; Java chokes on colons in the version string of the jarfile manifest.
;; And GAE chokes on everything else.
;;
(def the-version
  "A map of version information."
  (letfn [(frob [{:keys [release path]}]
            [(last (str/split path #"/")) release])]
    (let [built     (-> (OffsetDateTime/now)
                        (.truncatedTo ChronoUnit/SECONDS)
                        .toInstant .toString)
          commit    (util/shell! "git" "rev-parse" "HEAD")
          committed (->> commit
                         (util/shell! "git" "show" "-s" "--format=%cI")
                         OffsetDateTime/parse .toInstant .toString)
          clean?    (util/do-or-nil-silently
                     (util/shell! "git" "diff-index" "--quiet" "HEAD"))]
      (into
       {:version          (or (System/getenv "WFL_VERSION") "devel")
        :commit           commit
        :committed        committed
        :built            built
        :user             (or (System/getenv "USER") "wfl")
        "pipeline-config" "c8d70e65260239932d2896fbd1a43b3f0bb68475"}
       (map frob [aou/workflow-wdl
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

(defn stage-environment-dot-clj
  "Stage the wfl/environments.clj file to generated SOURCES."
  [sources]
  (let [clj  "../derived/2p/pipeline-config/wfl/environments.clj"
        file (io/file clj)]
    (util/shell-io!
     "git" "-c" "advice.detachedHead=false" "-C" (.getParent file)
     "checkout" (the-version "pipeline-config"))
    (let [out (io/file sources (.getName file))]
      (io/make-parents out)
      (io/copy file out))))

(defn prebuild
  "Stage any needed resources on the class path."
  [_opts]
  (let [api "../derived/api"]
    (pprint the-version)
    (stage-environment-dot-clj (io/file api "src" "wfl"))
    (write-the-version-file (io/file api "resources" "wfl") the-version))
  (System/exit 0))

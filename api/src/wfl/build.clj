(ns wfl.build
  "Build support originating in build.boot."
  (:require [clojure.data.xml :as xml]
            [clojure.java.io :as io]
            [clojure.pprint :refer [pprint]]
            [clojure.string :as str]
            [wfl.module.aou :as aou]
            [wfl.module.wgs :as wgs]
            [wfl.module.xx :as xx]
            [wfl.module.sg :as sg]
            [wfl.util :as util]
            [wfl.wfl :as wfl])
  (:import [java.time OffsetDateTime]
           [java.time.temporal ChronoUnit]))

(def second-party "../derived/2p")
(def derived      "../derived/api")

;; Java chokes on colons in the version string of the jarfile manifest.
;; And GAE chokes on everything else.
;;
(def the-version
  "A map of version information."
  (let [built     (-> (OffsetDateTime/now)
                      (.truncatedTo ChronoUnit/SECONDS)
                      .toInstant .toString)
        commit    (util/shell! "git" "rev-parse" "HEAD")
        committed (->> commit
                       (util/shell! "git" "show" "-s" "--format=%cI")
                       OffsetDateTime/parse .toInstant .toString)
        clean?    (util/do-or-nil-silently
                   (util/shell! "git" "diff-index" "--quiet" "HEAD"))]
    {:version   (or (System/getenv "WFL_VERSION") "devel")
     :commit    commit
     :committed committed
     :built     built
     :user      (or (System/getenv "USER") wfl/the-name)}))

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
  #::pom{:artifactId  (name wfl/artifactId)
         :description "WFL manages workflows."
         :groupId     (namespace wfl/artifactId)
         :name        "WorkFlow Launcher"
         :url         "https://github.com/broadinstitute/wfl.git"
         :version     (:version the-version)})

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
          (->> (spit out))))))

(defn find-repos
  "Return a map of wfl/the-github-repos clones.
   Omit [[wfl/the-name]]'s repo since it isn't needed
   for the version."
  []
  (let [the-github-repos-no-wfl (dissoc wfl/the-github-repos wfl/the-name)]
    (into {}
          (for [repo (keys the-github-repos-no-wfl)]
            (let [dir (str/join "/" [second-party repo])]
              [repo (util/shell! "git" "-C" dir "rev-parse" "HEAD")])))))

(defn stage-some-files
  "Stage some files from CLONES to generated SOURCES."
  [clones sources]
  (letfn [(clone [repo file] (io/file (str/join "/" [clones repo]) file))
          (stage [dir file]
            (let [out (io/file dir (.getName file))]
              (io/make-parents out)
              (io/copy file out)))]
    (let [environments (clone "pipeline-config" "wfl/environments.clj")]
      (util/shell-io!
       "git" "-c" "advice.detachedHead=false" "-C" (.getParent environments)
       "checkout" "3f182c0b06ee5f2dfebf15ed8b12d513027878ae")
      (stage sources environments))))

(defn prebuild
  "Stage any needed resources on the class path."
  [_opts]
  (letfn [(frob [{:keys [release path]}]
            [(last (str/split path #"/")) release])]
    (let [wdls      [aou/workflow-wdl wgs/workflow-wdl xx/workflow-wdl sg/workflow-wdl]
          clones    (find-repos)
          sources   (io/file derived "src" "wfl")
          resources (io/file derived "resources" "wfl")
          edn       (merge the-version clones (into {} (map frob wdls)))]
      (pprint edn)
      (stage-some-files second-party sources)
      (write-the-version-file resources edn))))

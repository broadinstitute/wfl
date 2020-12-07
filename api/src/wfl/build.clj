(ns wfl.build
  "Build support originating in build.boot."
  (:require [clojure.data.xml :as xml]
            [clojure.java.io :as io]
            [clojure.pprint :refer [pprint]]
            [clojure.string :as str]
            [wfl.main :as main]
            [wfl.module.aou :as aou]
            [wfl.module.wgs :as wgs]
            [wfl.module.xx :as xx]
            [wfl.util :as util]
            [wfl.wdl :as wdl]
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

(defn cromwellify-wdl
  "Cromwellify the WDL from warp in CLONES to RESOURCES."
  [clones resources {:keys [release top] :as _wdl}]
  (let [dp (str/join "/" [clones "warp"])]
    (util/shell-io!
     "git" "-c" "advice.detachedHead=false" "-C" dp "checkout" release)
    (let [[directory in-wdl in-zip] (wdl/cromwellify (io/file dp top))]
      (when directory
        (try (let [out-wdl (.getPath (io/file resources (.getName in-wdl)))
                   out-zip (str (util/unsuffix out-wdl ".wdl") ".zip")]
               (io/make-parents out-zip)
               (.renameTo in-wdl (io/file out-wdl))
               (.renameTo in-zip (io/file out-zip)))
             (finally (util/delete-tree directory)))))))

(defn stage-some-files
  "Stage some files from CLONES to generated SOURCES and RESOURCES."
  [clones sources resources]
  (letfn [(clone [repo file] (io/file (str/join "/" [clones repo]) file))
          (stage [dir file]
            (let [out (io/file dir (.getName file))]
              (io/make-parents out)
              (io/copy file out)))]
    (let [environments (clone "pipeline-config" "wfl/environments.clj")]
      (stage resources (clone "warp" "tasks/broad/CopyFilesFromCloudToCloud.wdl"))
      (util/shell-io!
       "git" "-c" "advice.detachedHead=false" "-C" (.getParent environments)
       "checkout" "3f182c0b06ee5f2dfebf15ed8b12d513027878ae")
      (stage sources environments))))

(defn prebuild
  "Stage any needed resources on the class path."
  [_opts]
  (letfn [(frob [{:keys [release top] :as _wdl}]
            [(last (str/split top #"/")) release])]
    (let [wdls      [aou/workflow-wdl wgs/workflow-wdl xx/workflow-wdl]
          clones    (find-repos)
          sources   (io/file derived "src" "wfl")
          resources (io/file derived "resources" "wfl")
          edn       (merge the-version clones (into {} (map frob wdls)))]
      (pprint edn)
      (stage-some-files second-party sources resources)
      (run! (partial cromwellify-wdl second-party resources) wdls)
      (write-the-version-file resources edn))))

(ns wfl.boot
  "Stuff moved out of build.boot."
  (:require [clojure.pprint :refer [pprint]]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [wfl.main :as main]
            [wfl.module.ukb :as ukb]
            [wfl.module.wgs :as wgs]
            [wfl.module.aou :as aou]
            [wfl.module.xx :as xx]
            [wfl.util :as util]
            [wfl.wdl :as wdl]
            [wfl.wfl :as wfl])
  (:import [java.time OffsetDateTime]
           [java.time.temporal ChronoUnit]))

;; Java chokes on colons in the version string of the jarfile manifest.
;; And GAE chokes on everything else.
;;
(defn make-the-version
  "Make a map of version information."
  []
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

(defn make-the-pom
  "Make the Project Object Model for this program from THE-VERSION."
  [the-version]
  {:description "WFL manages workflows."
   :project     wfl/artifactId
   :url         (:primary (wfl/the-github-repos wfl/the-name))
   :version     (:version the-version)})

(defn make-the-manifest
  "Make the manifest map for the jar file derived from THE-POM."
  [the-pom]
  (let [keywords [:description :url :version]]
    (assoc (zipmap (map (comp str/capitalize name) keywords)
                   ((apply juxt keywords) the-pom))
           "Application-Name" (str/capitalize wfl/the-name)
           "Multi-Release" "true")))

(defn find-repos
  "Return a map of wfl/the-github-repos clones.

   Specifically omits [[wfl/the-name]]'s repo since it isn't needed
   for the version."
  [second-party]
  (let [the-github-repos-no-wfl (dissoc wfl/the-github-repos wfl/the-name)]
    (into {}
          (for [repo (keys the-github-repos-no-wfl)]
            (let [dir (str/join "/" [second-party repo])]
              [repo (util/shell! "git" "-C" dir "rev-parse" "HEAD")])))))

(defn cromwellify-wdl
  "Cromwellify the WDL from dsde-pipelines in CLONES to RESOURCES.
   TODO: remove this function once the WARP transition is done."
  [clones resources {:keys [release top] :as _wdl}]
  (let [dp (str/join "/" [clones "dsde-pipelines"])]
    (util/shell-io! "git" "-C" dp "checkout" release)
    (let [[directory in-wdl in-zip] (wdl/cromwellify (io/file dp top))]
      (when directory
        (try (let [out-wdl (.getPath (io/file resources (.getName in-wdl)))
                   out-zip (str (util/unsuffix out-wdl ".wdl") ".zip")]
               (io/make-parents out-zip)
               (.renameTo in-wdl (io/file out-wdl))
               (.renameTo in-zip (io/file out-zip)))
             (finally (util/delete-tree directory)))))))

(defn cromwellify-warp-wdl
  "Cromwellify the WDL from warp in CLONES to RESOURCES."
  [clones resources {:keys [release top] :as _wdl}]
  (let [dp (str/join "/" [clones "warp"])]
    (util/shell-io! "git" "-C" dp "checkout" release)
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
      (stage resources (clone "dsde-pipelines" "tasks/CopyFilesFromCloudToCloud.wdl"))
      (util/shell-io! "git" "-C" (.getParent environments)
                      "checkout" "ad2a1b6b0f16d0e732dd08abcb79eccf4913c8d8")
      (stage sources environments))))

;; Hack: (delete-tree directory) is a hack.
;;
(defn manage-version-and-resources
  "Use VERSION to stage any needed RESOURCES on the class path."
  [version second-party derived]
  (letfn [(frob [{:keys [release top] :as _wdl}]
            [(last (str/split top #"/")) release])]
    (let [wdls [ukb/workflow-wdl aou/workflow-wdl]
          warp-wdls [wgs/workflow-wdl xx/workflow-wdl]
          clones (find-repos second-party)
          sources (io/file derived "src" "wfl")
          resources (io/file derived "resources" "wfl")
          edn (merge version clones (into {} (map frob wdls)))]
      (pprint edn)
      (stage-some-files second-party sources resources)
      (run! (partial cromwellify-wdl second-party resources) wdls)
      (run! (partial cromwellify-warp-wdl second-party resources) warp-wdls)
      (write-the-version-file resources edn))))

(defn main
  "Run this with ARGS."
  [& args]
  (apply main/-main args))

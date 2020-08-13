(ns zero.boot
  "Stuff moved out of build.boot."
  (:require [clojure.pprint :refer [pprint]]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [zero.main :as main]
            [zero.module.ukb :as ukb]
            [zero.module.wgs :as wgs]
            [zero.module.aou :as aou]
            [zero.module.xx :as xx]
            [zero.util :as util]
            [zero.wdl :as wdl]
            [zero.zero :as zero])
  (:import [java.time OffsetDateTime]
           [java.time.temporal ChronoUnit]
           [java.util UUID]))

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
     :user      (or (System/getenv "USER") zero/the-name)}))

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
   :project     zero/artifactId
   :url         (:primary (zero/the-github-repos zero/the-name))
   :version     (:version the-version)})

(defn make-the-manifest
  "Make the manifest map for the jar file derived from THE-POM."
  [the-pom]
  (let [keywords [:description :url :version]]
    (assoc (zipmap (map (comp str/capitalize name) keywords)
                   ((apply juxt keywords) the-pom))
           "Application-Name" (str/capitalize zero/the-name)
           "Multi-Release" "true")))

(defn find-repos
  "Return a map of zero/the-github-repos clones.

   Specifically omits [[zero/the-name]]'s repo since it isn't needed
   for the version."
  [second-party]
  (let [the-github-repos-no-wfl (dissoc zero/the-github-repos zero/the-name)]
    (into {}
          (for [repo (keys the-github-repos-no-wfl)]
            (let [dir (str/join "/" [second-party repo])]
              [repo (util/shell! "git" "-C" dir "rev-parse" "HEAD")])))))

(defn cromwellify-wdl
  "Cromwellify the WDL from dsde-pipelines in CLONES to RESOURCES."
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
                      "checkout" "9fecc4601b8e8c4812ec97e2bbc02d56e99c3a8f")
      (stage sources environments))))

;; Hack: (delete-tree directory) is a hack.
;;
(defn manage-version-and-resources
  "Use VERSION to stage any needed RESOURCES on the class path."
  [version second-party derived]
  (letfn [(frob [{:keys [release top] :as _wdl}]
            [(last (str/split top #"/")) release])]
    (let [wdls [ukb/workflow-wdl wgs/workflow-wdl xx/workflow-wdl aou/workflow-wdl]
          clones (find-repos second-party)
          sources (io/file derived "src" "zero")
          resources (io/file derived "resources" "zero")
          edn (merge version clones (into {} (map frob wdls)))]
      (pprint edn)
      (stage-some-files second-party sources resources)
      (run! (partial cromwellify-wdl second-party resources) wdls)
      (write-the-version-file resources edn))))

(defn main
  "Run this with ARGS."
  [& args]
  (apply main/-main args))

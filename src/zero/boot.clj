(ns zero.boot
  "Stuff moved out of build.boot."
  (:require [clojure.pprint :refer [pprint]]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [zero.environments :as env]
            [zero.main :as main]
            [zero.module.ukb :as ukb]
            [zero.module.wgs :as wgs]
            [zero.module.xx :as xx]
            [zero.server :as server]
            [zero.service.postgres :as postgres]
            [zero.util :as util]
            [zero.wdl :as wdl]
            [zero.zero :as zero])
  (:import [java.time OffsetDateTime]
           [java.time.temporal ChronoUnit]
           [java.util UUID]
           [java.util.zip ZipEntry ZipFile ZipOutputStream]))

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
        clean?    (util/do-or-nil
                    (util/shell! "git" "diff-index" "--quiet" "HEAD"))]
    {:version   (-> (if clean? committed built)
                    .toLowerCase
                    (str/replace #"[^-a-z0-9]" "-"))
     :commit    commit
     :committed committed
     :built     built
     :build     (-> "build.txt" slurp str/trim Integer/parseInt)
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
   :project     'org.broadinstitute/wfl
   :url         (zero/the-github-repos zero/the-name)
   :version     (:version the-version)})

(defn make-the-manifest
  "Make the manifest map for the jar file derived from THE-POM."
  [the-pom]
  (let [keywords [:description :url :version]]
    (assoc (zipmap (map (comp str/capitalize name) keywords)
                   ((apply juxt keywords) the-pom))
           "Application-Name" (str/capitalize zero/the-name))))

(defn clone-repos
  "Return a map of zero/the-github-repos clones in a :tmp directory.
   Delete the :tmp directory at some point."
  []
  (let [tmp (str "CLONE_" (UUID/randomUUID))]
    (letfn [(clone [url] (util/shell-io! "git" "-C" tmp "clone" url))]
      (io/make-parents (io/file tmp "Who cares, really?"))
      (run! clone (vals zero/the-github-repos)))
    (into {:tmp tmp}
          (for [repo (keys zero/the-github-repos)]
            (let [dir (str/join "/" [tmp repo])]
              [repo (util/shell! "git" "-C" dir "rev-parse" "HEAD")])))))

(defn cromwellify-wdl
  "Cromwellify the WDL from dsde-pipelines in CLONES to RESOURCES."
  [clones resources {:keys [release top] :as _wdl}]
  (let [dp (str/join "/" [clones "dsde-pipelines"])]
    (util/shell-io! "git" "-C" dp"checkout" release)
    (let [[directory in-wdl in-zip] (wdl/cromwellify (io/file dp top))]
      (when directory
        (try (let [out-wdl (.getPath (io/file resources (.getName in-wdl)))
                   out-zip (str (util/unsuffix out-wdl ".wdl") ".zip")]
               (io/make-parents out-zip)
               (.renameTo in-wdl (io/file out-wdl))
               (.renameTo in-zip (io/file out-zip)))
             (finally (util/delete-tree directory)))))))

(defn stage-some-files
  "Stage some files from CLONES to RESOURCES."
  [clones resources]
  (letfn [(clone [repo file] (io/file (str/join "/" [clones repo]) file))
          (stage [in]
            (let [out (io/file resources (.getName in))]
              (io/make-parents out)
              (io/copy in out)))]
    (stage (clone "dsde-pipelines" "tasks/CopyFilesFromCloudToCloud.wdl"))
    (stage (clone "pipeline-config" "zero/environments.clj"))))

(defn adapterize-wgs
  "Wrap the released WGS WDL in a new workflow that copy outputs and
  stage the new .wdl and .zip files in RESOURCES."
  [resources]
  (letfn [(zipify [wdl] (str (util/unsuffix (.getPath wdl) ".wdl") ".zip"))]
    (let [src-wgs (io/file (:top wgs/workflow-wdl))
          wgs-wdl (io/file resources (.getName src-wgs))
          adapter (io/file "wdl/ExternalWholeGenomeReprocessing.wdl")
          adapted (io/file resources (.getName adapter))
          in-zip  (io/file (zipify wgs-wdl))
          out-zip (io/file (zipify adapted))
          cffctc  (io/file resources "CopyFilesFromCloudToCloud.wdl")]
      (io/copy adapter adapted)
      (with-open [in  (ZipFile. in-zip)
                  out (ZipOutputStream. (io/output-stream out-zip))]
        (doseq [wdl [wgs-wdl cffctc]]
          (with-open [r (io/reader wdl)]
            (.putNextEntry out (ZipEntry. (.getName wdl)))
            (io/copy r out)))
        (doseq [wdl (enumeration-seq (.entries in))]
          (with-open [r (.getInputStream in wdl)]
            (.putNextEntry out (ZipEntry. (.getName wdl)))
            (io/copy r out)))))))

;; Hack: (delete-tree directory) is a hack.
;;
(defn manage-version-and-resources
  "Use VERSION to stage any needed RESOURCES on the class path."
  [version resources]
  (letfn [(frob [{:keys [release top] :as _wdl}]
            [(last (str/split top #"/")) release])]
    (let [wdls [ukb/workflow-wdl wgs/workflow-wdl xx/workflow-wdl]
          {:keys [tmp] :as clones} (clone-repos)
          directory (io/file resources "zero")
          edn (merge version
                     (dissoc clones :tmp)
                     (into {} (map frob wdls)))]
      (pprint edn)
      (try (util/delete-tree directory)
           (stage-some-files tmp directory)
           (run! (partial cromwellify-wdl tmp directory) wdls)
           (adapterize-wgs directory)
           (write-the-version-file directory edn)
           (finally (util/delete-tree (io/file tmp))))
      (spit "./build.txt" (-> version :build inc (str \newline))))))

(defn google-app-engine-configure
  "Write a GAE configuration for JAR in ENV to FILE. "
  [env file jar]
  (let [cmd ["java" "-Xmx${GAE_MEMORY_MB}m" "-jar" jar "server" "${PORT}"]]
    (util/spit-yaml
      file
      {:env_variables     (server/env_variables env)
       :service           zero/the-name
       :runtime           :java11
       :entrypoint        (str/join \space cmd)
       :instance_class    :F2
       :automatic_scaling {:max_concurrent_requests 20
                           :min_instances            2}
       :handlers [{:url "/swagger/(.*\\.(png|html|js|map|css))$"
                   :static_files "swagger-ui/\\1"
                   :upload "swagger-ui/.*\\.(png|html|js|map|css)$"}
                  {:url "/(.*\\.(js|css|png|jpg|ico|woff2|eot|ttf|map))$"
                   :static_files "dist/\\1"
                   :upload "dist/.*\\.(js|css|png|jpg|ico|woff2|eot|ttf|map)$"}
                  {:url "/"
                   :static_files "dist/index.html"
                   :upload "dist/index.html"}
                  {:url "/.*"
                   :script :auto
                   :secure :always}]}
      jar ""
      "https://cloud.google.com/appengine/docs/standard/java11/config/appref"
      (str "https://medium.com/@Leejjon_net/"
           "migrate-a-jersey-based-micro-service-to-java-11-"
           "and-deploy-to-app-engine-7ba41a835992"))))

(defn google-app-engine-deploy
  "Deploy to Google App Engine in ENVIRONMENT."
  [environment]
  (let [env       (zero/throw-or-environment-keyword! environment)
        project   (get-in env/stuff [env :server :project])
        version   (:version (zero/get-the-version))
        jar       (str zero/the-name "-" version ".jar")
        directory (io/file (str "GAE_" (UUID/randomUUID)))
        yaml      (io/file directory "app.yaml")]
    (try
      (io/make-parents yaml)
      (util/copy-directory (io/file "target/swagger-ui") directory)
      (google-app-engine-configure env yaml jar)
      (io/copy (io/file (io/file "target") jar) (io/file directory jar))
      (util/shell-io! "npm" "install" "--prefix" "ui")
      (util/shell-io! "npm" "run" "build" "--prefix" "ui")
      (util/copy-directory (io/file "ui/dist") directory)
      (postgres/run-liquibase env)
      (util/shell-io! "gcloud" "--quiet" "app" "deploy" (.getPath yaml)
                      (str "--project=" project) (str "--version=" version))
      (finally (util/delete-tree directory)))))

(defn main
  "Run this with ARGS."
  [& args]
  (apply main/-main args))

(ns zero.boot
  "Stuff moved out of build.boot."
  (:require [clojure.pprint :refer [pprint]]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [zero.environments :as env]
            [zero.main :as main]
            [zero.server :as server]
            [zero.service.postgres :as postgres]
            [zero.util :as util]
            [zero.wdl :as wdl]
            [zero.zero :as zero])
  (:import [java.time OffsetDateTime]
           [java.util UUID]))

(def environments-file
  "Path to the environments.clj file at build time."
  {:release "bf621a8bb27be9433622777e489fcbb652161586"
   :top     (str zero/pipeline-config "zero/environments.clj")})

(def dsde-pipelines-version
  {:release "b06bd3214b4be5cc9babf38f7679d0fb36968542"})

(def workflow-wdls
  "The workflow WDLs that Zero manages."
  [zero.module.ukb/workflow-wdl
   zero.module.wgs/workflow-wdl
   zero.module.xx/workflow-wdl])

(defn clone-repo
  "Clone the GIT repository at URL into DESTINATION."
  [url destination]
  (util/shell-io! "git" "clone" url destination))

;; Java chokes on colons in the version string of the jarfile manifest.
;; And GAE chokes on everything else.
;;
(defn make-the-version
  "Make a map of version information."
  []
  (letfn [(git [& more]
            (apply util/shell! (into ["git" "rev-parse" "--verify"] more)))
          (clean-version [timestamp]
            (str/replace timestamp #"[^-a-z0-9]" "-"))]
    (let [build-time (str (OffsetDateTime/now))
          changes? (not-empty (util/shell! "git" "status" "-s"))
          commit (git "HEAD")
          commit-time (util/shell! "git" "show" "-s" "--format=%cI" commit)
          commit-time-utc (-> commit-time
                              OffsetDateTime/parse
                              .toInstant
                              .toString
                              .toLowerCase)]
      (apply array-map
             :version         (if changes? (clean-version build-time) (clean-version commit-time-utc))
             :time            build-time
             :build           (-> "build.txt" slurp str/trim Integer/parseInt)
             :user            (or (System/getenv "USER") zero/the-name)
             :zero            commit
             :dsde-pipelines  (:release dsde-pipelines-version)
             :pipeline-config (:release environments-file)
             (->> workflow-wdls
                  (map (juxt (comp wdl/workflow-name :top) :release))
                  (apply concat))))))

(defn hack-write-the-version-hack
  "Write THE-VERSION to the RESOURCES directory."
  [resources the-version]
  (let [zero (io/file resources zero/the-name)
        file (io/file zero "version.edn")]
    (io/make-parents file)
    (with-open [out (io/writer file)]
      (binding [*out* out]
        (pprint the-version)))))

(defn make-the-pom
  "Make the Project Object Model for this program from THE-VERSION."
  [the-version]
  {:description "Zero manages workflows."
   :project     'org.broadinstitute/zero
   :url         (zero/the-github-repos zero/the-name)
   :version     (:version the-version)})

(defn make-the-manifest
  "Make the manifest map for the jar file derived from THE-POM."
  [the-pom]
  (let [keywords [:description :url :version]]
    (assoc (zipmap (map (comp str/capitalize name) keywords)
                   ((apply juxt keywords) the-pom))
           "Application-Name" (str/capitalize zero/the-name))))

(defn cromwellify-wdls-to-zip-hack
  "Stage WDL files and dependencies in RESOURCES."
  [resources wdl version]
  (util/shell-io! "git" "-C" zero/dsde-pipelines "checkout" version)
  (let [path (wdl/cromwellify-wdl-resources-hack wdl)
        [directory wdl zip] (wdl/cromwellify wdl)]
    (when directory
      (try (let [wf-wdl  (io/file resources (path ".wdl"))
                 imports (io/file resources (path ".zip"))]
             (io/make-parents imports)
             (.renameTo wdl wf-wdl)
             (.renameTo zip imports))
           (finally (util/delete-tree directory))))))

;; Hack: delete-tree is a hack.
;;
(defn manage-version-and-resources
  "Stage any extra resources needed on the class path."
  [version resources]
  (let [directory (io/file resources zero/the-name)
        {:keys [top release]} environments-file
        clj (io/file directory (last (str/split top #"/")))
        hack (partial apply cromwellify-wdls-to-zip-hack resources)]
    (pprint version)
    (util/delete-tree directory)
    (clone-repo zero/pipeline-config-url zero/pipeline-config)
    (util/shell-io! "git" "-C" zero/pipeline-config "checkout" release)
    (io/make-parents clj)
    (io/copy (io/file top) clj)
    (clone-repo zero/dsde-pipelines-url zero/dsde-pipelines)
    (run! hack (map (juxt :top :release) workflow-wdls))
    (hack-write-the-version-hack resources version)
    (spit "./build.txt" (-> version :build inc (str \newline)))
    (util/delete-tree (io/file zero/dsde-pipelines)
                      (io/file zero/pipeline-config))))

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
                  {:url "/(.*\\.(js|css|png|jpg|ico|woff2|eot|ttf))$"
                   :static_files "dist/\\1"
                   :upload "dist/.*\\.(js|css|png|jpg|ico|woff2|eot|ttf)$"}
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
        directory (io/file (str "gae_" (UUID/randomUUID)))
        yaml      (io/file directory "app.yaml")]
    (try
      (io/make-parents yaml)
      (util/copy-directory (io/file "target/swagger-ui") directory)
      (google-app-engine-configure env yaml jar)
      (io/copy (io/file (io/file "target") jar) (io/file directory jar))
      (util/shell-io! "npm" "install" "--prefix" "ui")
      (util/shell-io! "npm" "run" "build" "--prefix" "ui")
      (util/copy-directory (io/file "ui/dist") directory)
      (postgres/run-liquibase-migration env)
      (util/shell-io! "gcloud" "--quiet" "app" "deploy" (.getPath yaml)
                      (str "--project=" project) (str "--version=" version))
      (finally (util/delete-tree directory)))))

(defn main
  "Run this with ARGS."
  [& args]
  (apply main/-main args))

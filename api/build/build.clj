(ns build
  (:require [clojure.data.xml :as xml]
            [clojure.java.io :as io]
            [clojure.pprint :refer [pprint]]
            [clojure.string :as str]
            [wfl.module.aou :as aou]
            [wfl.module.wgs :as wgs]
            [wfl.module.xx :as xx]
            [wfl.module.sg :as sg]
            [wfl.service.firecloud :as firecloud]
            [wfl.util :as util])
  (:import [java.time OffsetDateTime]
           [java.time.temporal ChronoUnit]))

;; Java chokes on colons in the version string of the jarfile manifest.
;; And GAE chokes on everything else.
;;
(def the-version
  "A map of version information."
  (letfn [(frob [{:keys [release path]}] [(util/basename path) release])]
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
        {:version   (or (System/getenv "WFL_VERSION") "devel")
         :commit    commit
         :committed committed
         :built     built
         :user      (or (System/getenv "USER") "wfl")}
        (map frob [aou/workflow-wdl
                   sg/workflow-wdl
                   wgs/workflow-wdl
                   xx/workflow-wdl])))))

(defn write-the-version-file
  "Write VERSION.edn into the RESOURCES directory."
  [resources version]
  (let [file (io/file resources "wfl" "version.edn")]
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
        file     (io/file resources "workflows" (str workflow ".edn"))]
    (when-not (.exists file)
      (printf "generating workflow description %s\n" (util/basename wdl))
      (io/make-parents file)
      (with-open [out (io/writer file)]
        (binding [*out* out]
          (-> (slurp wdl) firecloud/describe-workflow pprint))))))

(defn ^:private find-wdls []
  (letfn [(list-wdls [folder]
            (->> (file-seq (io/file folder))
                 (map #(.getCanonicalPath %))
                 (filter #(= (util/extension %) "wdl"))))]
  (mapcat list-wdls ["resources" "test/resources"])))

(defn prebuild
  "Stage any needed resources on the class path."
  [_opts]
  (let [derived        (str/join "/" [".." "derived" "api"])
        resources      (io/file derived "resources")
        test-resources (io/file derived "test" "resources")]
    (pprint the-version)
    (write-the-version-file resources the-version)
    (run! #(write-workflow-description test-resources %) (find-wdls))
    (System/exit 0)))

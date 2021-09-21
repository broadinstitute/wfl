(ns build
  (:require [clojure.data.xml      :as xml]
            [clojure.java.io       :as io]
            [clojure.pprint        :refer [pprint]]
            [clojure.string        :as str]
            [wfl.service.firecloud :as firecloud]
            [wfl.util              :as util])
  (:import [java.time OffsetDateTime]
           [java.time.temporal ChronoUnit]))

(defmacro do-or-nil-silently
  "Value of `body` or `nil` if it throws, without logging exceptions.
  See also [[wfl.util/do-or-nil]]."
  [& body]
  `(try (do ~@body)
        (catch Exception x#)))

;; Java chokes on colons in the version string of the jarfile manifest.
;; And GAE chokes on everything else.
;;
(def the-version
  "A map of version information."
  (delay
    (let [built     (-> (OffsetDateTime/now)
                        (.truncatedTo ChronoUnit/SECONDS)
                        .toInstant .toString)
          commit    (util/shell! "git" "rev-parse" "HEAD")
          committed (->> commit
                         (util/shell! "git" "show" "-s" "--format=%cI")
                         OffsetDateTime/parse .toInstant .toString)]
      {:version   (or (System/getenv "WFL_VERSION") "devel")
       :commit    commit
       :committed committed
       :built     built
       :user      (or (System/getenv "USER") "wfl")})))

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
           :version     (:version @the-version)}))

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
      (println "describing" (util/basename wdl) "to" (.getPath file))
      (io/make-parents file)
      (let [edn (-> wdl slurp firecloud/describe-workflow)]
        (spit file (with-out-str
                     (println (str/join [";; (write-workflow-description"
                                         \space resources \space wdl ")"]))
                     (println ";;")
                     (pprint edn)))))))

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
    (try
      (pprint @the-version)
      (write-the-version-file resources @the-version)
      (run! #(write-workflow-description test-resources %) (find-wdls))
      (System/exit 0)
      (catch Throwable t
        (pprint t)
        (System/exit 1)))))

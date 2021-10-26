(ns wfl.wfl
  "Stuff specific to WFL."
  (:require [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.string :as str]))

(def the-name
  "Use this name to refer to this program."
  "wfl")

(def artifactId
  "The artifact ID for this program."
  'org.broadinstitute/wfl)

(def the-github-repos
  "Map WFL source repo names to their URLs.
  Primary URL should be tried first, and there's a second
  URL that works in GitHub actions to try as a backup."
  (let [repos ["pipeline-config" "wfl"]
        broad "github.com:broadinstitute/"
        urls (fn [x] {:primary        (str "git@" broad x)
                      :actions-backup (str "git@" x "." broad x)})]
    (zipmap repos (map urls repos))))

(defn get-the-version
  "Return version information from the JAR."
  []
  (or (some-> (str/join "/" ["wfl" "version.edn"])
              io/resource
              slurp
              str/trim
              edn/read-string)
      {:version "SOME BOGUS VERSION"
       :commit "aaaaabbbbbcccccdddddeeeeefffffggggghhhhh"}))

(defn get-wfl-resource
  "Return a wfl resource."
  [path]
  (or (some-> (str/join "/" ["wfl" path])
              io/resource
              slurp)
      (slurp (str/join "/" ["." "resources" path]))))

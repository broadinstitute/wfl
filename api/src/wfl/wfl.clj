(ns wfl.wfl
  "Stuff specific to WFL."
  (:require [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.tools.logging :as log]
            [wfl.environments :as env]))

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
  (let [repos ["warp" "wfl" "pipeline-config"]
        urls (fn [x] {:primary (str "git@github.com:broadinstitute/" x),
                      :actions-backup (str "git@" x ".github.com:broadinstitute/" x)})]
    (zipmap repos (map urls repos))))

(defn error-or-environment-keyword
  "Error string or the keyword for a valid ENVIRONMENT."
  [environment]
  (let [result (keyword environment)
        tab (str/join (repeat 2 \space))]
    (if (env/environments result) result
        (letfn [(stringify [[k v]] (str tab (name k) \newline tab tab v))]
          (let [error (if environment
                        (format "%s: Error: %s is not a valid environment."
                                the-name environment)
                        (format "%s: Error: Must specify an environment."
                                the-name))]
            (log/error error)
            (log/debug "The valid environments are:")
            (log/debug (->> env/environments
                            (sort-by first)
                            (map stringify)
                            (str/join \newline)))
            error)))))

(defn throw-or-environment-keyword!
  "Throw or the keyword for a valid ENVIRONMENT."
  [environment]
  (let [result (error-or-environment-keyword environment)]
    (when-not (keyword? result)
      (throw (IllegalArgumentException. result)))
    result))

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

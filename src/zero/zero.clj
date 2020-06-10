(ns zero.zero
  "Stuff specific to Zero."
  (:require [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [zero.environments :as env]))

(def the-name
  "Use this name to refer to this program."
  "wfl")

(def artifactId
  "The artifact ID for this program."
  'org.broadinstitute/zero)

(def the-github-repos
  "Map Zero source repo names to their URLs"
  (let [repos ["wfl" "dsde-pipelines" "pipeline-config"]
        git   (partial str "git@github.com:broadinstitute/")]
    (zipmap repos (map git repos))))

(def the-other-github-repos
  "Map Zero source repo names to their URLs with aliases"
  (let [repos ["wfl" "dsde-pipelines" "pipeline-config"]
        git (fn [x] (str "git@" x ".github.com:broadinstitute/" x))]
    (zipmap repos (map git repos))))

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
            (binding [*out* *err*]
              (println error)
              (println (format "%s: The valid environments are:" the-name))
              (println (->> env/environments
                            (sort-by first)
                            (map stringify)
                            (str/join \newline))))
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
  (or (some-> (str/join "/" ["zero" "version.edn"])
              io/resource
              slurp
              str/trim
              edn/read-string)
      {:version "SOME BOGUS VERSION"}))

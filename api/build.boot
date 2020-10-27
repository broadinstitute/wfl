#!/usr/bin/env boot

;; Define the dependencies in the following order.
;; 0. Clojure core libraries: clojure first then alphabetical.
;; 1. Other libraries in alphabetical order by artifact.
;; 2. Crazy long (Google) artifact names in alphabetical order.
;; 3. Test scoped dependencies again in alphabetical order.

(def second-party "../derived/2p")
(defn derived
  ([] "../derived/api")
  ([part] (str (derived) "/" part)))

(set-env!
 :resource-paths #{(derived "resources") "resources"}
 :source-paths #{(derived "src") "src"}
 :target-path (derived "target")
 :repositories
 '[["broad"
    {:url "https://broadinstitute.jfrog.io/broadinstitute/ext-release-local"}]
   ["clojars"
    {:url "https://repo.clojars.org/"}]
   ["maven-central"
    {:url "https://repo1.maven.org/maven2"}]]
 :dependencies                         ; See the ordering note above.
 '[[org.clojure/clojure                       "1.10.1"]
   [org.clojure/data.json                     "0.2.6"]
   [org.clojure/data.xml                      "0.2.0-alpha6"]
   [org.clojure/data.zip                      "0.1.3"]
   [org.clojure/java.jdbc                     "0.7.8"]
   [org.clojure/tools.logging                 "1.1.0"]
   [amperity/vault-clj                        "0.5.1"]
   [buddy/buddy-sign                          "3.1.0"]
   [clj-commons/clj-yaml                      "0.7.0"]
   [clj-http                                  "3.7.0"]
   [clj-time                                  "0.15.1"]
   [com.fasterxml.jackson.core/jackson-core   "2.10.0"]
   [metosin/muuntaja                          "0.6.6"]
   [metosin/reitit                            "0.5.10" :exclusions [metosin/ring-swagger-ui]]
   [metosin/ring-http-response                "0.9.1"]
   [org.apache.tika/tika-core                 "1.19.1"]
   [org.apache.logging.log4j/log4j-api        "2.13.3"]
   [org.apache.logging.log4j/log4j-core       "2.13.3"]
   [org.apache.logging.log4j/log4j-slf4j-impl "2.13.3"]
   [org.postgresql/postgresql                 "42.2.9"]
   [org.slf4j/slf4j-api                       "1.7.30"]
   [ring-oauth2                               "0.1.4"]
   [ring/ring-core                            "1.7.1"]
   [ring/ring-defaults                        "0.3.2"]
   [ring/ring-devel                           "1.7.1"]
   [ring/ring-jetty-adapter                   "1.7.1"]
   [ring/ring-json                            "0.5.0"]
   [com.google.cloud.sql/postgres-socket-factory            "1.0.15"]
   [com.google.cloud.sql/jdbc-socket-factory-core           "1.0.15"]
   [com.google.auth/google-auth-library-oauth2-http         "0.20.0"]
   [adzerk/boot-test                  "1.2.0"   :scope "test"]
   [onetom/boot-lein-generate         "0.1.3"   :scope "test"]])

(require '[boot.lein])
(require 'wfl.boot)

(def the-version (wfl.boot/make-the-version))

(defn manage-version-and-resources
  "Add WDL files and version information to /resources/."
  []
  (let [resources (clojure.java.io/file (derived "resources"))]
    (wfl.boot/manage-version-and-resources the-version second-party (derived))
    (with-pre-wrap fileset (-> fileset (add-resource resources) commit!))))

;; So boot.lein can pick up the project name and version.
;;
(def the-pom (wfl.boot/make-the-pom the-version))
(task-options! pom the-pom)

(deftask prebuild
  ""
  []
  (do
    (boot.lein/generate)
    (manage-version-and-resources)))

(deftask build
  "Build this."
  []
  (comp
   (pom)
   (aot :namespace '#{wfl.main})
   (uber)
   (jar :main 'wfl.main :manifest (wfl.boot/make-the-manifest the-pom))
   (target :dir #{(derived "target")})))

(defn -main
  "Run this."
  [& args]
  (apply wfl.boot/main args))

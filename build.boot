#!/usr/bin/env boot

;; Define the dependencies in the following order.
;; 0. Clojure core libraries: clojure first then alphabetical.
;; 1. Other libraries in alphabetical order by artifact.
;; 2. Crazy long (Google) artifact names in alphabetical order.
;; 3. Test scoped dependencies again in alphabetical order.

(set-env!
  :resource-paths #{"src"}
  :source-paths #{"resources"}
  :target-path "target"
  :repositories
  '[["broad"
     {:url "https://broadinstitute.jfrog.io/broadinstitute/ext-release-local"}]
    ["clojars"
     {:url "https://repo.clojars.org/"}]
    ["maven-central"
     {:url "https://repo1.maven.org/maven2"}]]
  :dependencies                         ; See the ordering note above.
  '[[org.clojure/clojure                     "1.10.1"]
    [org.clojure/data.json                   "0.2.6"]
    [org.clojure/data.xml                    "0.2.0-alpha6"]
    [org.clojure/data.zip                    "0.1.3"]
    [org.clojure/java.jdbc                   "0.7.8"]
    [amperity/vault-clj                      "0.5.1"]
    [clj-commons/clj-yaml                    "0.7.0"]
    [clj-http                                "3.7.0"]
    [clj-time                                "0.15.1"]
    [com.fasterxml.jackson.core/jackson-core "2.10.0"]
    [metosin/muuntaja                        "0.6.6"]
    [metosin/reitit                          "0.4.2" :exclusions [metosin/ring-swagger-ui]]
    [metosin/ring-swagger-ui                 "3.20.1"]
    [org.apache.tika/tika-core               "1.19.1"]
    [org.liquibase/liquibase-cdi             "3.8.5"]
    [org.liquibase/liquibase-core            "3.8.5"]
    [org.postgresql/postgresql               "42.2.9"]
    [org.slf4j/slf4j-simple                  "1.7.28"]
    [ring-oauth2                             "0.1.4"]
    [ring/ring-core                          "1.7.1"]
    [ring/ring-defaults                      "0.3.2"]
    [ring/ring-devel                         "1.7.1"]
    [ring/ring-jetty-adapter                 "1.7.1"]
    [ring/ring-json                          "0.5.0"]
    [com.google.cloud.sql/postgres-socket-factory            "1.0.15"]
    [com.google.cloud.sql/jdbc-socket-factory-core           "1.0.15"]
    [com.google.auth/google-auth-library-oauth2-http         "0.15.0"]
    [adzerk/boot-test                  "1.2.0"   :scope "test"]
    [onetom/boot-lein-generate         "0.1.3"   :scope "test"]])

(require '[boot.lein])
(boot.lein/generate)

(when-not (.exists (clojure.java.io/file "./zero"))
  (dosh "ln" "-s" "./build.boot" "./zero"))

(require '[adzerk.boot-test :as boot-test])

(require 'zero.boot)

(def the-version (zero.boot/make-the-version))

(deftask manage-version-and-resources
  "Add WDL files and version information to /resources/."
  []
  (let [resources (clojure.java.io/file "./resources")]
    (zero.boot/manage-version-and-resources the-version resources)
    (with-pre-wrap fileset (-> fileset (add-resource resources) commit!))))

;; So boot.lein can pick up the project name and version.
;;
(def the-pom (zero.boot/make-the-pom the-version))
(task-options! pom the-pom)

(deftask build
  "Build this."
  []
  (comp (manage-version-and-resources)
        (pom)
        (aot :namespace '#{zero.main})
        (uber)
        (jar :main 'zero.main :manifest (zero.boot/make-the-manifest the-pom))
        (target)))

(deftask deploy
  "Deploy this to Google App Engine in ENVIRONMENT."
  []
  (zero.boot/google-app-engine-deploy
    (or (first *args*) "gotc-dev")))

(deftask test
  "Add directory to :source-paths so BOOT-TEST/TEST can find it."
  []
  (merge-env! :source-paths #{"test"})
  (adzerk.boot-test/test))

(defn -main
  "Run this."
  [& args]
  (apply zero.boot/main args))

(ns wfl.environments
  "Map environment to various values here.")

;; Note: The LOAD form at the end of this file effectively ignores the
;; rest of the content in this file in favor of loading a new
;; wfl.environments namespace out of classpath resources.
;;
;; The rest of the file is here just to bootstrap development.
;;
;; I hope this works for you.  Let me know when it doesn't.

(def cromwell-label-keys
  "Use these keys to extract Cromwell labels from workflow inputs."
  [:data_type
   :project
   :regulatory_designation
   :sample_name
   :version])

(def development
  "Some development environment."
  {:cromwell
   {:url "https://my-development-cromwell.example.com"
    :labels cromwell-label-keys}
   :google
   {:jes_roots ["gs://cromwell-execution-path"]
    :noAddress false
    :projects ["a-development-project" "another-development-project"]}
   :server
   {:project "development"
    :vault   "path/to/server/secrets"}
   :data-repo
   {:url  "https://datarepo-development.example.com"
    :service-account "datarepo-service-account.iam.gserviceaccount.com"}})

(def production
  "Some production environment."
  {:cromwell
   {:url    "https://my-production-cromwell.example.com"
    :labels cromwell-label-keys}
   :google
   {:jes_roots   ["gs://cromwell-execution-path"]
    :noAddress       false
    :projects ["a-production-project" "another-production-project"]}
   :server
   {:project "production"
    :vault   "path/to/server/secrets"}
   :data-repo
   {:url  "https://datarepo-production.example.com"
    :service-account "datarepo-service-account.iam.gserviceaccount.com"}})

(def debug
  "Put debug hacks here."
  development)

(def stuff
  "Map ENVIRONMENT and so on to vault paths, URLs and so on."
  (letfn [(make [m e] (let [kw  (keyword e)
                            var (resolve (symbol e))]
                        (-> (var-get var)
                            (assoc :doc (:doc (meta var)) :keyword kw :name e)
                            (->> (assoc m kw)))))]
    (reduce make {} ["debug" "development" "production"])))

(def environments
  "Map valid values for :ENVIRONMENT to their doc strings."
  (zipmap (keys stuff) (map :doc (vals stuff))))

;; Ignore all of that above and instead load from the classpath.
;;
;; See wfl.build/stage-some-files for more.
;;
(load "environments")

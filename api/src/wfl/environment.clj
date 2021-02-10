(ns wfl.environment
  "Map environment to various values here.")

;; TODO: `is-known-cromwell-url?` in modules means new projects require new releases
;;  since this is baked in code. Can we improve this?

(def cromwell-label-keys
  "Use these keys to extract Cromwell labels from workflow inputs."
  [:data_type
   :project
   :regulatory_designation
   :sample_name
   :version])

(def gotc-dev-cromwell
  {:labels cromwell-label-keys
   :monitoring_script "gs://broad-gotc-prod-cromwell-monitoring/monitoring.sh"
   :url "https://cromwell-gotc-auth.gotc-dev.broadinstitute.org"})

(def debug
  "A local environment for development and debugging."
  {:cromwell gotc-dev-cromwell
   :data-repo
   {:service-account "jade-k8-sa@broad-jade-dev.iam.gserviceaccount.com"
    :url "https://jade.datarepo-dev.broadinstitute.org"}
   :google
   {:jes_roots ["gs://broad-gotc-dev-cromwell-execution"]
    :projects ["broad-gotc-dev"]}
   :server
   {:project "broad-gotc-dev"
    :service-account "secret/dsde/gotc/dev/wfl/wfl-non-prod-service-account.json"
    :vault "secret/dsde/gotc/dev/zero"}})

(def stuff
  "Map ENVIRONMENT and so on to vault paths and JDBC URLs and so on."
  (letfn [(make [m e] (let [kw  (keyword e)
                            var (resolve (symbol e))]
                        (-> (var-get var)
                            (assoc :doc (:doc (meta var)) :keyword kw :name e)
                            (->> (assoc m kw)))))]
    (reduce make {} ["debug"])))

(def environments
  "Map valid values for :ENVIRONMENT to their doc strings."
  (zipmap (keys stuff) (map :doc (vals stuff))))

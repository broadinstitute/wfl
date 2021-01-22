(ns wfl.environments
  "Map environment to various values here.")

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

(def gotc-prod-cromwell
  {:labels cromwell-label-keys
   :monitoring_script "gs://broad-gotc-prod-cromwell-monitoring/monitoring.sh"
   :url "https://cromwell-gotc-auth.gotc-prod.broadinstitute.org"})

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

(def gotc-dev
  "Development: that is, gotc-dev."
  {:cromwell gotc-dev-cromwell
   :data-repo
   {:service-account "jade-k8-sa@broad-jade-dev.iam.gserviceaccount.com"
    :url "https://jade.datarepo-dev.broadinstitute.org"}
   :google
   {:jes_roots ["gs://broad-gotc-dev-cromwell-execution"]
    :projects ["broad-gotc-dev"]}
   :server
   {:project "broad-gotc-dev"
    :vault "secret/dsde/gotc/dev/zero"}})

(def gotc-prod
  "Production: that is, gotc-prod."
  (let [prefix   "broad-realign-"
        projects (map (partial str prefix "execution0")      (range 1  6))
        buckets  (map (partial str prefix "short-execution") (range 1 11))
        roots    (map (partial format "gs://%s/") buckets)]
    {:cromwell gotc-prod-cromwell
     :google
     {:jes_roots (vec roots)
      :noAddress false
      :projects (vec projects)}
     :google_account_vault_path "secret/dsde/gotc/prod/picard/picard-account.pem"
     :vault_token_path "gs://broad-dsp-gotc-prod-tokens/picardsa.token"}))

(def aou-dev
  "Process Arrays Samples in gotc-dev Cromwell."
  {:cromwell gotc-dev-cromwell
   :google
   {:jes_roots ["gs://broad-gotc-dev-cromwell-execution"]
    :noAddress false
    :projects ["broad-exomes-dev1"]}
   :server
   {:project "broad-gotc-dev"
    :vault "secret/dsde/gotc/dev/zero"}
   :vault_token_path "gs://broad-dsp-gotc-arrays-dev-tokens/arrayswdl.token"})

(def aou-prod
  "Process Arrays Samples in AOU-prod Cromwell."
  {:cromwell {:labels cromwell-label-keys
              :monitoring_script nil
              :url "https://cromwell-aou.gotc-prod.broadinstitute.org"}
   :google
   {:jes_roots ["gs://broad-aou-exec-storage"]
    :noAddress false
    :projects ["broad-aou-arrays-compute1" "broad-aou-arrays-compute2"]}
   :server
   {:project "broad-aou"
    :vault "secret/dsde/gotc/prod/aou/zero"}
   :vault_token_path "gs://broad-dsp-gotc-arrays-prod-tokens/arrayswdl.token"})

(def arrays-dev
  "Process Arrays Samples in gotc-dev Cromwell."
  {:cromwell gotc-dev-cromwell
   :google
   {:jes_roots ["gs://broad-gotc-dev-cromwell-execution"]
    :noAddress false
    :projects ["broad-exomes-dev1"]}
   :server
   {:project "broad-gotc-dev"
    :vault "secret/dsde/gotc/dev/zero"}
   :vault_token_path "gs://broad-dsp-gotc-arrays-dev-tokens/arrayswdl.token"})

(def arrays-prod
  "Process Arrays Samples in AOU-prod Cromwell."
  {:cromwell gotc-prod-cromwell
   :google
   {:noAddress false
    :projects ["broad-gotc-prod"]}
   :server
   {:project "broad-gotc-prod"
    :vault "secret/dsde/gotc/prod/zero"}
   :vault_token_path "gs://broad-dsp-gotc-arrays-prod-tokens/arrayswdl.token"})

(def wgs-dev
  "Reprocess Genomes on the gotc-dev Cromwell."
  {:cromwell gotc-dev-cromwell
   :google
   {:jes_roots ["gs://broad-gotc-dev-cromwell-execution"]
    :noAddress false
    :projects ["broad-exomes-dev1"]}
   :server
   {:project "broad-gotc-dev"
    :vault "secret/dsde/gotc/dev/zero"}
   :google_account_vault_path "secret/dsde/gotc/dev/picard/picard-account.pem"
   :vault_token_path "gs://broad-dsp-gotc-dev-tokens/picardsa.token"})

(def wgs-prod
  "Reprocess production Genomes on the gotc-prod Cromwell."
  (let [prefix   "broad-realign-"
        projects (map (partial str prefix "execution0")      (range 1  6))
        buckets  (map (partial str prefix "short-execution") (range 1 11))
        roots    (map (partial format "gs://%s/") buckets)]
    {:cromwell gotc-prod-cromwell
     :google
     {:jes_roots (vec roots)
      :noAddress false
      :projects (vec projects)}
     :google_account_vault_path "secret/dsde/gotc/prod/picard/picard-account.pem"
     :vault_token_path "gs://broad-dsp-gotc-prod-tokens/picardsa.token"}))

(def xx-dev
  "Reprocess External Exomes on the gotc-dev Cromwell."
  {:cromwell gotc-dev-cromwell
   :google
   {:jes_roots ["gs://broad-gotc-dev-cromwell-execution"]
    :noAddress false
    :projects ["broad-exomes-dev1"]}
   :google_account_vault_path "secret/dsde/gotc/dev/picard/picard-account.pem"
   :vault_token_path "gs://broad-dsp-gotc-dev-tokens/picardsa.token"})

(def xx-prod
  "Reprocess External Exomes on the gotc-prod Cromwell."
  (let [prefix   "broad-realign-"
        projects (map (partial str prefix "execution0")      (range 1  6))
        buckets  (map (partial str prefix "short-execution") (range 1 11))
        roots    (map (partial format "gs://%s/") buckets)]
    {:cromwell gotc-prod-cromwell
     :google
     {:jes_roots (vec roots)
      :noAddress false
      :projects (vec projects)}
     :google_account_vault_path "secret/dsde/gotc/prod/picard/picard-account.pem"
     :vault_token_path "gs://broad-dsp-gotc-prod-tokens/picardsa.token"}))

(def stuff
  "Map ENVIRONMENT and so on to vault paths and JDBC URLs and so on."
  (letfn [(make [m e] (let [kw  (keyword e)
                            var (resolve (symbol e))]
                        (-> (var-get var)
                            (assoc :doc (:doc (meta var)) :keyword kw :name e)
                            (->> (assoc m kw)))))]
    (reduce make {} ["debug"
                     "gotc-dev" "gotc-prod"
                     "aou-dev" "aou-prod"
                     "arrays-dev" "arrays-prod"
                     "wgs-dev" "wgs-prod"
                     "xx-dev" "xx-prod"])))

(def environments
  "Map valid values for :ENVIRONMENT to their doc strings."
  (zipmap (keys stuff) (map :doc (vals stuff))))

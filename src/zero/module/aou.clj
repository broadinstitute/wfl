(ns zero.module.aou
  "Process Arrays for the All Of Us project."
  (:require [clojure.java.io :as io]
            [clojure.data.json :as json]
            [clojure.java.jdbc :as jdbc]
            [clojure.set :as set]
            [clojure.string :as str]
            [zero.environments :as env]
            [zero.module.all :as all]
            [zero.references :as references]
            [zero.service.cromwell :as cromwell]
            [zero.service.postgres :as postgres]
            [zero.service.gcs :as gcs]
            [zero.util :as util]
            [zero.wdl :as wdl]
            [zero.zero :as zero])
  (:import [java.time OffsetDateTime]
           [java.util UUID]))

(def pipeline "AllOfUsArrays")

(def workflow-wdl
  "The top-level WDL file and its version."
  {:release "Arrays_v1.9"
   :top     "pipelines/arrays/single_sample/Arrays.wdl"})

(def cromwell-label-map
  "The WDL label applied to Cromwell metadata."
  {(keyword (str zero/the-name "-aou"))
   (wdl/workflow-name (:top workflow-wdl))})

(def cromwell-label
  "The WDL label applied to Cromwell metadata."
  (let [[key value] (first cromwell-label-map)]
    (str (name key) ":" value)))

#_(def per-sample
  "The sample specific inputs for arrays."
  {:chip_well_barcode       "7991775143_R01C01"
   :sample_alias            "NA12878"
   :red_idat_cloud_path     "gs://broad-gotc-test-storage/arrays/HumanExome-12v1-1_A/idats/7991775143_R01C01/7991775143_R01C01_Red.idat"
   :green_idat_cloud_path   "gs://broad-gotc-test-storage/arrays/HumanExome-12v1-1_A/idats/7991775143_R01C01/7991775143_R01C01_Grn.idat"
   :analysis_version_number 1
   :sample_lsid             "broadinstitute.org:bsp.dev.sample:NOTREAL.NA12878"
   :reported_gender         "Female"
   :params_file             "gs://broad-gotc-test-storage/arrays/HumanExome-12v1-1_A/inputs/7991775143_R01C01/params.txt"})

(def chip-metadata
  "Chip Metadata inputs for arrays."
  {:bead_pool_manifest_file     "gs://broad-gotc-test-storage/arrays/metadata/HumanExome-12v1-1_A/HumanExome-12v1-1_A.bpm"
   :extended_chip_manifest_file "gs://broad-gotc-test-storage/arrays/metadata/HumanExome-12v1-1_A/HumanExome-12v1-1_A.1.3.extended.csv"
   :cluster_file                "gs://broad-gotc-test-storage/arrays/metadata/HumanExome-12v1-1_A/HumanExomev1_1_CEPH_A.egt"
   :gender_cluster_file         "gs://broad-gotc-test-storage/arrays/metadata/HumanExome-12v1-1_A/HumanExomev1_1_gender.egt"
   :zcall_thresholds_file       "gs://broad-gotc-test-storage/arrays/metadata/HumanExome-12v1-1_A/IBDPRISM_EX.egt.thresholds.txt"}
  )

(def fingerprinting
  "Fingerprinting inputs for arrays."
  {:fingerprint_genotypes_vcf_file       nil
   :fingerprint_genotypes_vcf_index_file nil
   :haplotype_database_file              "gs://gcp-public-data--broad-references/hg19/v0/Homo_sapiens_assembly19.haplotype_database.txt"
   :variant_rsids_file                   "gs://broad-references-private/hg19/v0/Homo_sapiens_assembly19.haplotype_database.snps.list"}
  )

(def genotype-concordance
  "Genotype Concordance inputs for arrays."
  {:control_sample_vcf_file       "gs://broad-gotc-test-storage/arrays/controldata/NA12878.vcf.gz"
   :control_sample_vcf_index_file "gs://broad-gotc-test-storage/arrays/controldata/NA12878.vcf.gz.tbi"
   :control_sample_intervals_file "gs://broad-gotc-test-storage/arrays/controldata/NA12878.interval_list"
   :control_sample_name           "NA12878"})

(def other-inputs
  "Miscellaneous inputs for arrays."
  {:call_rate_threshold              0.98
   :genotype_concordance_threshold   0.98
   :contamination_controls_vcf       nil
   :subsampled_metrics_interval_list nil
   :disk_size                        100
   :preemptible_tries                3})

(defn- map-aou-environment
  "Map AOU-ENV to envrionment for inputs preparation."
  [aou-env]
  ({:aou-dev "dev" :aou-prod "prod"} aou-env))

(defn array-inputs
  "Array inputs for ENVIRONMENT that do not depend on the input file."
  [environment]
  {:vault_token_path (get-in env/stuff [environment :vault_token_path])
   :environment      (map-aou-environment environment)})

(comment
  (array-inputs :aou-dev))

(defn make-inputs
  "Return inputs for AoU Arrays processing in ENVIRONMENT."
  [environment]
  (let [inputs (merge references/hg19-arrays-references
                      #_per-sample
                      chip-metadata
                      fingerprinting
                      genotype-concordance
                      other-inputs
                      (array-inputs environment))]
    (util/prefix-keys inputs :Arrays)))

(defn make-options
  "Return options for aou arrays pipeline."
  []
  {:read_from_cache            true
   :write_to_cache             true
   :default_runtime_attributes {:zones "us-central1-a us-central1-b us-central1-c us-central1-f"}})

(defn active-or-done-objects
  "Query by PRIMARY-VALS to get a set of active or done objects from Cromwell in ENVIRONMENT."
  [environment {:keys [analysis_version_number chip_well_barcode] :as primary-vals}]
  (prn (format "%s: querying Cromwell in %s" zero/the-name environment))
  (let [primary-keys [:analysis_version_number
                      :chip_well_barcode]
        md           (partial cromwell/metadata environment)]
    (letfn [(active? [metadata]
              (let [cromwell-id                       (metadata :id)
                    analysis-verion-chip-well-barcode (-> cromwell-id md :inputs
                                                          (select-keys primary-keys))]
                (when analysis-verion-chip-well-barcode
                  (let [found-analysis-version-number (:analysis_version_number analysis-verion-chip-well-barcode)
                        found-chip-well-barcode       (:chip_well_barcode analysis-verion-chip-well-barcode)]
                    (when (and (= found-analysis-version-number analysis_version_number)
                               (= found-chip-well-barcode chip_well_barcode))
                      analysis-verion-chip-well-barcode)))))]
      (->> {:label  cromwell-label
            :status ["On Hold" "Running" "Submitted" "Succeeded"]}
           (cromwell/query environment)
           (keep active?)
           (filter seq)
           set))))

(comment
  (active-objects :gotc-dev {:analysis_version_number 1, :chip_well_barcode "7991775143_R01C01"}))

(defn extract-and-validate-per-sample-inputs
  "Extract valid per-sample inputs from OBJECT"
  [object]
  (let [per-sample (select-keys object [:analysis_version_number
                                        :bead_pool_manifest_file
                                        :chip_well_barcode
                                        :cluster_file
                                        :extended_chip_manifest_file
                                        :gender_cluster_file
                                        :green_idat_cloud_path
                                        :params_file
                                        :red_idat_cloud_path
                                        :reported_gender
                                        :sample_alias
                                        :sample_lsid
                                        :zcall_thresholds_file])]
    (if (every? (fn [x] (not (nil? x))) (vals per-sample))
      per-sample
      (throw (Exception. (format "Invalid sample inputs %s" per-sample))))))

(comment
  (extract-and-validate-per-sample-inputs {:chip_well_barcode nil,
                                           :rex               "greetings!"})
  (extract-and-validate-per-sample-inputs {:chip_well_barcode           "7991775143_R01C01",
                                           :rex                         "greetings!"
                                           :bead_pool_manifest_file     "gs://broad-gotc-test-storage/arrays/metadata/HumanExome-12v1-1_A/HumanExome-12v1-1_A.bpm",
                                           :analysis_version_number     1,
                                           :extended_chip_manifest_file "gs://broad-gotc-test-storage/arrays/metadata/HumanExome-12v1-1_A/HumanExome-12v1-1_A.1.3.extended.csv",
                                           :red_idat_cloud_path         "gs://broad-gotc-test-storage/arrays/HumanExome-12v1-1_A/idats/7991775143_R01C01/7991775143_R01C01_Red.idat",
                                           :zcall_thresholds_file       "gs://broad-gotc-test-storage/arrays/metadata/HumanExome-12v1-1_A/IBDPRISM_EX.egt.thresholds.txt",
                                           :reported_gender             "Female",
                                           :cluster_file                "gs://broad-gotc-test-storage/arrays/metadata/HumanExome-12v1-1_A/HumanExomev1_1_CEPH_A.egt",
                                           :sample_lsid                 "broadinstitute.org:bsp.dev.sample:NOTREAL.NA12878",
                                           :sample_alias                "NA12878",
                                           :green_idat_cloud_path       "gs://broad-gotc-test-storage/arrays/HumanExome-12v1-1_A/idats/7991775143_R01C01/7991775143_R01C01_Grn.idat",
                                           :params_file                 "gs://broad-gotc-test-storage/arrays/HumanExome-12v1-1_A/inputs/7991775143_R01C01/params.txt",
                                           :gender_cluster_file         "gs://broad-gotc-test-storage/arrays/metadata/HumanExome-12v1-1_A/HumanExomev1_1_gender.egt"}))

(defn really-submit-one-workflow
  "Submit one workflow to ENVIRONMENT."
  [environment per-sample-inputs]
  (let [path (wdl/hack-unpack-resources-hack (:top workflow-wdl))]
    (cromwell/submit-workflow
      environment
      (io/file (:dir path) (path ".wdl"))
      (io/file (:dir path) (path ".zip"))
      (merge (make-inputs environment)
             (extract-and-validate-per-sample-inputs per-sample-inputs))
      (make-options)
      cromwell-label-map)))

(comment
  (make-options)
  (keys (make-inputs :aou-dev))
  (really-submit-one-workflow :aou-dev {}))

#_(defn update-workload!
    "Use transaction TX to update WORKLOAD statuses."
    [tx workload])

(defn add-workload!
  "Use transaction TX to add the workload described by BODY to the database DB.
   Due to the continuous nature of the AoU dataflow, this function will only
   create a new workload table if it does not exist otherwise append records
   to the existing one."
  [tx body]
  (let [{:keys [creator cromwell pipeline project]} body
        {:keys [release top]} workflow-wdl
        {:keys [commit version]} (zero/get-the-version)
        probe-result (jdbc/query tx ["SELECT * FROM workload WHERE project = ? AND wdl = ? AND release = ?"
                                     project top release])]
    (if (seq probe-result)
      ;; if a table already exists, we return its uuid and the name
      (let [[{:keys [id uuid]}] probe-result
            table (format "%s_%09d" pipeline id)]
        [uuid table])
      ;; if the expected table doesn't exist, we create record + table and return uuid and name
      (let [[{:keys [id uuid]}]
            (jdbc/insert! tx :workload {:commit   commit
                                        :creator  creator
                                        :cromwell cromwell
                                        :input    "aou-inputs-placeholder"
                                        :output   "aou-outputs-placeholder"
                                        :project  project
                                        :release  release
                                        :uuid     (UUID/randomUUID)
                                        :version  version
                                        :wdl      top})
            table (format "%s_%09d" pipeline id)
            kind  (format (str/join " " ["UPDATE workload"
                                         "SET pipeline = '%s'::pipeline"
                                         "WHERE id = %s"]) pipeline id)
            work  (format "CREATE TABLE %s OF %s (PRIMARY KEY (analysis_version_number, chip_well_barcode))" table pipeline)]
        (jdbc/update! tx :workload {:items table} ["id = ?" id])
        (jdbc/db-do-commands tx [kind work])
        [uuid table]))))

(comment
  (jdbc/with-db-transaction [tx (postgres/zero-db-config)]
                            (let [body {:creator  "rex"
                                        :cromwell "http://cromwell-gotc-auth.gotc-dev.broadinstitute.org/"
                                        :project  "gotc-dev"
                                        :pipeline "AllOfUsArrays"}]
                              (add-workload! tx body))))

(defn start-workload!
  "Use transaction TX to start the WORKLOAD by UUID. This is simply updating the
   workload table to mark a workload as 'started' so it becomes append-able."
  [tx {:keys [uuid] :as body}]
  (let [now (OffsetDateTime/now)]
    (jdbc/update! tx :workload {:started now} ["uuid = ?" uuid])))

(defn keep-primary-keys
  "Only return the primary keys of a SAMPLE."
  [sample]
  (select-keys sample [:chip_well_barcode :analysis_version_number]))

(defn remove-existing-samples!
  "Return set of SAMPLEs that are not registered in workload TABLE using transaction TX."
  [tx samples table]
  (letfn [(q [[left right]] (fn [it] (str left it right)))
          (extract-key-groups [l] [(map :chip_well_barcode l) (map :analysis_version_number l)])
          (make-query-group [v] (->> v (map (q "''")) (str/join ",") ((q "()"))))
          (assemble-query [query l] (format query (first l) (last l)))]
    (let [samples  (map keep-primary-keys samples)
          existing (->> samples
                        (extract-key-groups)
                        (map make-query-group)
                        (assemble-query (str/join " " ["SELECT * FROM" table "WHERE chip_well_barcode in %s"
                                                       "AND analysis_version_number in %s"]))
                        (jdbc/query tx)
                        (map keep-primary-keys)
                        set)]
      (set (remove existing samples)))))

(comment
  (jdbc/with-db-transaction [tx (postgres/zero-db-config)]
                            (let [samples [{:analysis_version_number     1,
                                            :chip_well_barcode           "7991775143_R01C01",
                                            :green_idat_cloud_path       "gs://broad-gotc-test-storage/arrays/HumanExome-12v1-1_A/idats/7991775143_R01C01/7991775143_R01C01_Grn.idat",
                                            :params_file                 "gs://broad-gotc-test-storage/arrays/HumanExome-12v1-1_A/inputs/7991775143_R01C01/params.txt",
                                            :red_idat_cloud_path         "gs://broad-gotc-test-storage/arrays/HumanExome-12v1-1_A/idats/7991775143_R01C01/7991775143_R01C01_Red.idat"
                                            :reported_gender             "Female"
                                            :sample_alias                "NA12878"
                                            :sample_lsid                 "broadinstitute.org:bsp.dev.sample:NOTREAL.NA12878"
                                            :bead_pool_manifest_file     "gs://broad-gotc-test-storage/arrays/metadata/HumanExome-12v1-1_A/HumanExome-12v1-1_A.bpm"
                                            :cluster_file                "gs://broad-gotc-test-storage/arrays/metadata/HumanExome-12v1-1_A/HumanExomev1_1_CEPH_A.egt"
                                            :zcall_thresholds_file       "gs://broad-gotc-test-storage/arrays/metadata/HumanExome-12v1-1_A/IBDPRISM_EX.egt.thresholds.txt"
                                            :gender_cluster_file         "gs://broad-gotc-test-storage/arrays/metadata/HumanExome-12v1-1_A/HumanExomev1_1_gender.egt"
                                            :extended_chip_manifest_file "gs://broad-gotc-test-storage/arrays/metadata/HumanExome-12v1-1_A/HumanExome-12v1-1_A.1.3.extended.csv"}]]
                              (remove-existing-samples! tx samples "allofusarrays_000000001"))))


(defn append-to-workload!
  "Use transaction TX to append the samples to a WORKLOAD identified by uuid.
   The workload needs to be marked as 'started' in order to be append-able, and
   any samples being added to the workload table will be submitted right away."
  [tx {:keys [notifications uuid environment] :as body}]
  (let [now                (OffsetDateTime/now)
        table-query-result (first (jdbc/query tx ["SELECT * FROM workload WHERE uuid = ?" uuid]))
        workload-started?  (:started table-query-result)
        table              (:items table-query-result)]

    (if (nil? workload-started?)
      ; throw when the workload does not exist or it hasn't been started yet
      (throw (Exception. (format "Workload %s is not started yet!" uuid)))
      ; insert the new samples into DB and start the workflows
      (let [primary-keys               (map keep-primary-keys notifications)
            primary-keys-notifications (zipmap primary-keys notifications)
            new-samples                (remove-existing-samples! tx primary-keys table)
            to-be-submitted            (->> primary-keys-notifications
                                            (keys)
                                            (keep new-samples)
                                            (select-keys primary-keys-notifications))]
        (letfn [(sql-rize [m] (-> m
                                  (assoc :updated now)
                                  keep-primary-keys))
                (submit! [sample]
                  [(really-submit-one-workflow environment sample) (keep-primary-keys sample)])
                (update! [tx [uuid {:keys [analysis_version_number chip_well_barcode] :as pks}]]
                  (when uuid
                    (jdbc/update! tx table
                                  {:updated now :uuid uuid}
                                  ["analysis_version_number = ? AND chip_well_barcode = ?" analysis_version_number
                                   chip_well_barcode])
                    {:updated                 (str now)
                     :uuid                    uuid
                     :analysis_version_number analysis_version_number
                     :chip_well_barcode       chip_well_barcode}))]
          (let [inserted            (jdbc/insert-multi! tx table (map sql-rize (vals to-be-submitted)))
                submitted-uuids-pks (map submit! (vals to-be-submitted))]
            (doall (map (partial update! tx) submitted-uuids-pks))))))))

(comment
  (jdbc/with-db-transaction [tx (postgres/zero-db-config)]
   (start-workload! tx {:uuid "aae87e30-8084-41b1-a8a5-7b0c5294be23"}))


  (jdbc/with-db-transaction [tx (postgres/zero-db-config)]
                            (let [body {:cromwell      "http://cromwell-gotc-auth.gotc-dev.broadinstitute.org/"
                                        :environment   :aou-dev
                                        :notifications [{:analysis_version_number     1,
                                                         :chip_well_barcode           "7991775143_R01C01",
                                                         :green_idat_cloud_path       "gs://broad-gotc-test-storage/arrays/HumanExome-12v1-1_A/idats/7991775143_R01C01/7991775143_R01C01_Grn.idat",
                                                         :params_file                 "gs://broad-gotc-test-storage/arrays/HumanExome-12v1-1_A/inputs/7991775143_R01C01/params.txt",
                                                         :red_idat_cloud_path         "gs://broad-gotc-test-storage/arrays/HumanExome-12v1-1_A/idats/7991775143_R01C01/7991775143_R01C01_Red.idat"
                                                         :reported_gender             "Female"
                                                         :sample_alias                "NA12878"
                                                         :sample_lsid                 "broadinstitute.org:bsp.dev.sample:NOTREAL.NA12878"
                                                         :bead_pool_manifest_file     "gs://broad-gotc-test-storage/arrays/metadata/HumanExome-12v1-1_A/HumanExome-12v1-1_A.bpm"
                                                         :cluster_file                "gs://broad-gotc-test-storage/arrays/metadata/HumanExome-12v1-1_A/HumanExomev1_1_CEPH_A.egt"
                                                         :zcall_thresholds_file       "gs://broad-gotc-test-storage/arrays/metadata/HumanExome-12v1-1_A/IBDPRISM_EX.egt.thresholds.txt"
                                                         :gender_cluster_file         "gs://broad-gotc-test-storage/arrays/metadata/HumanExome-12v1-1_A/HumanExomev1_1_gender.egt"
                                                         :extended_chip_manifest_file "gs://broad-gotc-test-storage/arrays/metadata/HumanExome-12v1-1_A/HumanExome-12v1-1_A.1.3.extended.csv"}
                                                        {:analysis_version_number     2,
                                                         :extra_rex                   "rex-test"
                                                         :chip_well_barcode           "7991775143_R01C01",
                                                         :green_idat_cloud_path       "gs://broad-gotc-test-storage/arrays/HumanExome-12v1-1_A/idats/7991775143_R01C01/7991775143_R01C01_Grn.idat",
                                                         :params_file                 "gs://broad-gotc-test-storage/arrays/HumanExome-12v1-1_A/inputs/7991775143_R01C01/params.txt",
                                                         :red_idat_cloud_path         "gs://broad-gotc-test-storage/arrays/HumanExome-12v1-1_A/idats/7991775143_R01C01/7991775143_R01C01_Red.idat"
                                                         :reported_gender             "Female"
                                                         :sample_alias                "NA12878"
                                                         :sample_lsid                 "broadinstitute.org:bsp.dev.sample:NOTREAL.NA12878"
                                                         :bead_pool_manifest_file     "gs://broad-gotc-test-storage/arrays/metadata/HumanExome-12v1-1_A/HumanExome-12v1-1_A.bpm"
                                                         :cluster_file                "gs://broad-gotc-test-storage/arrays/metadata/HumanExome-12v1-1_A/HumanExomev1_1_CEPH_A.egt"
                                                         :zcall_thresholds_file       "gs://broad-gotc-test-storage/arrays/metadata/HumanExome-12v1-1_A/IBDPRISM_EX.egt.thresholds.txt"
                                                         :gender_cluster_file         "gs://broad-gotc-test-storage/arrays/metadata/HumanExome-12v1-1_A/HumanExomev1_1_gender.egt"
                                                         :extended_chip_manifest_file "gs://broad-gotc-test-storage/arrays/metadata/HumanExome-12v1-1_A/HumanExome-12v1-1_A.1.3.extended.csv"}]
                                        :uuid          "aae87e30-8084-41b1-a8a5-7b0c5294be23"}]
                              (append-to-workload! tx body))))

(defn create-workload
  "Remember the workload specified by BODY."
  [body]
  (jdbc/with-db-transaction [tx (postgres/zero-db-config)]
                            (->> body
                                 (add-workload! tx)
                                 (conj ["SELECT * FROM workload WHERE uuid = ?"])
                                 (jdbc/query tx)
                                 first
                                 (filter second)
                                 (into {}))))

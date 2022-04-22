(ns wfl.system.v1-endpoint-test
  (:require [clojure.test               :refer [deftest is testing]]
            [clojure.data.json          :as json]
            [clojure.instant            :as instant]
            [clojure.set                :as set]
            [clojure.spec.alpha         :as s]
            [clojure.string             :as str]
            [wfl.api.handlers           :as handlers]
            [wfl.api.workloads]
            [wfl.debug                  :as debug]
            [wfl.environment            :as env]
            [wfl.executor               :as executor]
            [wfl.log                    :as log]
            [wfl.mime-type              :as mime-type]
            [wfl.module.staged          :as staged]
            [wfl.service.cromwell       :as cromwell]
            [wfl.service.datarepo       :as datarepo]
            [wfl.service.google.storage :as gcs]
            [wfl.tools.datasets         :as datasets]
            [wfl.tools.endpoints        :as endpoints]
            [wfl.tools.fixtures         :as fixtures]
            [wfl.tools.resources        :as resources]
            [wfl.tools.workflows        :as workflows]
            [wfl.tools.workloads        :as workloads]
            [wfl.util                   :as util])
  (:import [clojure.lang ExceptionInfo]
           [java.util UUID]))

(defn make-create-workload [make-request]
  (fn [] (endpoints/create-workload (make-request (UUID/randomUUID)))))

(def create-aou-workload (make-create-workload workloads/aou-workload-request))
(def create-sg-workload  (make-create-workload workloads/sg-workload-request))
(def create-wgs-workload (make-create-workload workloads/wgs-workload-request))
(def create-xx-workload  (make-create-workload workloads/xx-workload-request))

(defn create-copyfile-workload [src dst]
  (endpoints/create-workload (workloads/copyfile-workload-request src dst)))

(defn ^:private verify-succeeded-workflow
  [{:keys [inputs status] :as workflow}]
  (is (map? inputs) "Every workflow should have nested inputs")
  (is (every? workflow [:updated :uuid]))
  (is (not-every? workflow [:id]))
  (is (= "Succeeded" status)))

(defn ^:private verify-succeeded-workload
  [workload]
  (run! verify-succeeded-workflow (endpoints/get-workflows workload))
  (workloads/postcheck workload))

(defn ^:private verify-internal-properties-removed [workload]
  (let [workflows (endpoints/get-workflows workload)]
    (letfn [(go! [key]
              (is (util/absent? workload key)
                  (format "workload should not contain %s" key))
              (is (every? #(util/absent? % key) workflows)
                  (format "workflows should not contain %s" key)))]
      (run! go! [:id :items]))))

(deftest ^:parallel test-oauth2-endpoint
  (testing "The `oauth2_id` endpoint indeed provides an ID"
    (let [response (endpoints/get-oauth2-id)]
      (is (= (count response) 2))
      (is (some #(= % :oauth2-client-id) response))
      (is (some #(str/includes? % "apps.googleusercontent.com") response)))))

(deftest ^:parallel test-version-endpoint
  (is (every? (endpoints/version) [:built :commit :committed :user :version])))

(defn ^:private test-create-workload
  [request]
  (letfn [(test! [{:keys [pipeline] :as request}]
            (testing (format "calling api/v1/create with %s workload request"
                             pipeline)
              (let [{:keys [created creator executor started uuid] :as workload}
                    (endpoints/create-workload request)]
                (is uuid "workloads should be been assigned a uuid")
                (is created "should have a created timestamp")
                (is (= @workloads/email creator)
                    "creator inferred from auth token")
                (is (not started) "hasn't been started in cromwell")
                (letfn [(included [m] (select-keys m [:pipeline :project]))]
                  (is (= (included request) (included workload))))
                (is (= executor (or (:executor request) (:cromwell request)))
                    "lost track of executor/cromwell")
                (verify-internal-properties-removed workload))))]
    (test! request)
    (testing "passed :cromwell rather than :executor"
      (test! (set/rename-keys request {:executor :cromwell})))))

(deftest test-create-wgs-workload
  (test-create-workload (workloads/wgs-workload-request (UUID/randomUUID))))
(deftest test-create-aou-workload
  (test-create-workload (workloads/aou-workload-request (UUID/randomUUID))))

(deftest test-create-xx-workload
  (test-create-workload (workloads/xx-workload-request (UUID/randomUUID))))
(deftest test-create-sg-workload
  (test-create-workload (workloads/sg-workload-request (UUID/randomUUID))))
(deftest test-create-copyfile-workload
  (test-create-workload (workloads/copyfile-workload-request
                         "gs://fake-inputs/lolcats.txt"
                         "gs://fake-outputs/copied.txt")))

(defn ^:private test-start-workload
  [{:keys [uuid pipeline] :as workload}]
  (testing (format "calling api/v1/start with %s workload" pipeline)
    (let [workload (endpoints/start-workload workload)]
      (try
        (is (= uuid (:uuid workload)))
        (is (:started workload))
        (let [workflows (endpoints/get-workflows workload)]
          (is (every? :updated workflows))
          (is (every? :uuid workflows)))
        (verify-internal-properties-removed workload)
        (workloads/when-all-workflows-finish verify-succeeded-workload workload)
        (finally
          (endpoints/stop-workload workload))))))

(deftest ^:parallel test-start-wgs-workload
  (test-start-workload (create-wgs-workload)))
(deftest ^:parallel test-start-aou-workload
  (test-start-workload (create-aou-workload)))
(deftest ^:parallel test-start-xx-workload
  (test-start-workload (create-xx-workload)))
(deftest ^:parallel test-start-sg-workload
  (test-start-workload (create-sg-workload)))
(deftest ^:parallel test-start-copyfile-workload
  (fixtures/with-temporary-cloud-storage-folder fixtures/gcs-test-bucket
    (fn [url]
      (let [src (str url "input.txt")
            dst (str url "output.txt")]
        (-> (str/join "/" ["test" "resources" "copy-me.txt"])
            (gcs/upload-file src))
        (test-start-workload (create-copyfile-workload src dst))))))

(defn ^:private test-stop-workload
  [{:keys [pipeline] :as workload}]
  (testing (format "calling /stop with %s workload before /start" pipeline)
    (is (thrown-with-msg?
         ExceptionInfo #"400"
         (endpoints/stop-workload workload)))
    (testing (format "calling /stop with %s workload after /start" pipeline)
      (let [workload (endpoints/stop-workload
                      (endpoints/start-workload workload))]
        (is (:started workload))
        (is (:stopped workload))))))

(deftest ^:parallel test-stop-wgs-workload
  (test-stop-workload (create-wgs-workload)))
(deftest ^:parallel test-stop-aou-workload
  (test-stop-workload (create-aou-workload)))
(deftest ^:parallel test-stop-xx-workload
  (test-stop-workload (create-xx-workload)))
(deftest ^:parallel test-stop-sg-workload
  (test-stop-workload (create-sg-workload)))
(deftest ^:parallel test-stop-copyfile-workload
  (test-stop-workload (create-copyfile-workload "gs://b/in" "gs://b/out")))

(defn ^:private test-exec-workload
  [{:keys [pipeline] :as request}]
  (testing (format "calling api/v1/exec with %s workload request" pipeline)
    (let [{:keys [created creator started uuid] :as workload}
          (endpoints/exec-workload request)]
      (try
        (is uuid    "workloads should have a uuid")
        (is created "should have a created timestamp")
        (is started "should have a started timestamp")
        (is (= @workloads/email creator)
            "creator inferred from auth token")
        (letfn [(included [m] (select-keys m [:pipeline :project]))]
          (is (= (included request) (included workload))))
        (let [workflows (endpoints/get-workflows workload)]
          (is (every? :updated workflows))
          (is (every? :uuid workflows)))
        (verify-internal-properties-removed workload)
        (workloads/when-all-workflows-finish verify-succeeded-workload workload)
        (finally
          (endpoints/stop-workload workload))))))

(deftest ^:parallel test-exec-wgs-workload
  (test-exec-workload (workloads/wgs-workload-request (UUID/randomUUID))))
(deftest ^:parallel test-exec-wgs-workload-specifying-cromwell
  ;; All modules make use of the same code/behavior here, no need to spam Cromwell
  (test-exec-workload (-> (workloads/wgs-workload-request (UUID/randomUUID))
                          (set/rename-keys {:executor :cromwell}))))
(deftest ^:parallel test-exec-aou-workload
  (test-exec-workload (workloads/aou-workload-request (UUID/randomUUID))))
(deftest ^:parallel test-exec-xx-workload
  (test-exec-workload (workloads/xx-workload-request (UUID/randomUUID))))
(deftest ^:parallel test-exec-sg-workload
  (test-exec-workload (workloads/sg-workload-request (UUID/randomUUID))))

(deftest ^:parallel test-exec-copyfile-workload
  (fixtures/with-temporary-cloud-storage-folder fixtures/gcs-test-bucket
    (fn [url]
      (let [src (str url "input.txt")
            dst (str url "output.txt")]
        (-> (str/join "/" ["test" "resources" "copy-me.txt"])
            (gcs/upload-file src))
        (test-exec-workload (workloads/copyfile-workload-request src dst))))))

(defn ^:private retry-check-message-and-throw
  "Retry workflows in `workload` matching `filters`,
  catching any exception to verify its message
  before rethrowing."
  [workload message filters]
  (try
    (endpoints/retry-workflows workload filters)
    (catch Exception cause
      (is (= message (-> (ex-data cause) util/response-body-json :message))
          (str "Unexpected or missing exception message for filters "
               filters))
      (throw cause))))

(defn ^:private test-retry-legacy-workload-fails
  "Any non-staged (legacy) workload has not had retry functionality implemented,
  and should fail with an HTTP 501 if requested."
  [request]
  (let [workload (endpoints/create-workload request)]
    (letfn [(should-throw-501 [message status]
              (is (thrown-with-msg?
                   ExceptionInfo #"clj-http: status 501"
                   (retry-check-message-and-throw workload message {:status status}))
                  (str "Expecting 501 error for retry with status " status)))]
      (run! (partial should-throw-501
                     wfl.api.workloads/retry-unimplemented-error-message)
            cromwell/status?))))

(deftest ^:parallel test-retry-wgs-workload
  (test-retry-legacy-workload-fails (workloads/wgs-workload-request (UUID/randomUUID))))
(deftest ^:parallel test-retry-aou-workload
  (test-retry-legacy-workload-fails (workloads/aou-workload-request (UUID/randomUUID))))
(deftest ^:parallel test-retry-xx-workload
  (test-retry-legacy-workload-fails (workloads/xx-workload-request (UUID/randomUUID))))
(deftest ^:parallel test-retry-sg-workload
  (test-retry-legacy-workload-fails (workloads/sg-workload-request (UUID/randomUUID))))

(deftest ^:parallel test-append-to-aou-workload
  (let [await    (partial cromwell/wait-for-workflow-complete
                          @workloads/cromwell-url)
        workload (endpoints/exec-workload
                  (workloads/aou-workload-request (UUID/randomUUID)))]
    (try
      (testing "appending sample successfully launches an aou workflow"
        (is (->> workload
                 (endpoints/append-to-aou-workload [workloads/aou-sample])
                 (map (comp await :uuid))
                 (every? #{"Succeeded"})))
        (->> (endpoints/get-workload-status (:uuid workload))
             (workloads/when-all-workflows-finish verify-succeeded-workload)))
      (finally
        (endpoints/stop-workload workload)))))

(deftest test-bad-pipeline
  (let [request (-> (workloads/copyfile-workload-request
                     "gs://fake/in" "gs://fake/out")
                    (assoc :pipeline "geoff"))]
    (testing "create-workload! fails (400) with bad request"
      (is (thrown-with-msg? ExceptionInfo #"clj-http: status 400"
                            (endpoints/create-workload request))))
    (testing "exec-workload! fails (400) with bad request"
      (is (thrown-with-msg? ExceptionInfo #"clj-http: status 400"
                            (endpoints/exec-workload request))))))

(defn ^:private covid-workload-request
  "Build a covid workload request."
  []
  (let [terra-ns  (comp (partial str/join "/") (partial vector "wfl-dev"))
        workspace (terra-ns "CDC_Viral_Sequencing")
        source    {:name                   "Terra DataRepo"
                   :dataset                "79fc88f5-dcf4-48b0-8c01-615dfbc1c63a"
                   :table                  "flowcells"
                   :snapshotReaders        ["hornet@firecloud.org"]
                   :pollingIntervalMinutes 1}
        executor  {:name                "Terra"
                   :workspace           workspace
                   :methodConfiguration (terra-ns "sarscov2_illumina_full")
                   :fromSource          "importSnapshot"}
        sink      {:name           "Terra Workspace"
                   :workspace      workspace
                   :entityType     "assemblies"
                   :identifier     "flowcell_id"
                   :fromOutputs    (resources/read-resource
                                    "sarscov2_illumina_full/entity-from-outputs.edn")
                   :skipValidation true}]
    (workloads/staged-workload-request source executor sink)))

(defn instantify-timestamps
  "Replace timestamps at keys KS of map M with parsed #inst values."
  [m & ks]
  (reduce (fn [m k] (update m k instant/read-instant-timestamp)) m ks))

(deftest ^:parallel test-staged-workload
  (testing "/create staged workload"
    (let [workload-request (covid-workload-request)
          {:keys [creator started uuid] :as workload}
          (-> workload-request
              endpoints/create-workload
              (update :created instant/read-instant-timestamp))]
      (is (s/valid? ::staged/workload-request  workload-request))
      (is (s/valid? ::staged/workload-response workload))
      (verify-internal-properties-removed workload)
      (is (not started))
      (is (= @workloads/email creator))
      (testing "/workload status"
        (let [{:keys [started] :as response}
              (-> uuid endpoints/get-workload-status
                  (instantify-timestamps :created))]
          (is (not started))
          (verify-internal-properties-removed response)
          (is (s/valid? ::staged/workload-response response))))
      (testing "/workload all"
        (let [{:keys [started] :as response}
              (-> (endpoints/get-workloads)
                  (->> (filter (comp #{uuid} :uuid)))
                  first
                  (instantify-timestamps :created))]
          (is (not started))
          (verify-internal-properties-removed response)
          (is (s/valid? ::staged/workload-response response))))
      (testing "/retry staged workload"
        (letfn [(should-throw-400 [message filters]
                  (is (thrown-with-msg?
                       ExceptionInfo #"clj-http: status 400"
                       (retry-check-message-and-throw workload message filters))
                      (str "Expecting 400 error for retry with filters " filters)))]
          (let [status-unretriable "Running"
                status-retriable   "Failed"
                submission         (str (UUID/randomUUID))
                filters-invalid    [{}
                                    {:status status-unretriable}
                                    {:status status-retriable}
                                    {:submission submission :status status-unretriable}]
                filters-valid      [{:submission submission}
                                    {:submission submission :status status-retriable}]]
            (run! (partial should-throw-400
                           executor/terra-executor-retry-filters-invalid-error-message)
                  filters-invalid)
            (run! (partial should-throw-400
                           handlers/retry-no-workflows-error-message)
                  filters-valid))))
      (testing "/start staged workload"
        (let [{:keys [created started] :as response}
              (-> workload endpoints/start-workload
                  (instantify-timestamps :created :started))]
          (is (s/valid? ::staged/workload-response response))
          (is (inst? created))
          (is (inst? started))))
      (testing "/stop staged workload"
        (let [{:keys [created started stopped] :as response}
              (-> workload endpoints/stop-workload
                  (instantify-timestamps :created :started :stopped))]
          (is (s/valid? ::staged/workload-response response))
          (is (inst? created))
          (is (inst? started))
          (is (inst? stopped)))))))

(defn ^:private try-to-get-workflows
  "Try up to `n` times to summarize the workflows in `workload`."
  [n workload]
  (try (endpoints/get-workflows workload)
       (catch Throwable x
         (debug/trace [n workload x])
         (when (> n 0)
           (try-to-get-workflows (dec n) workload)))))

(defn ^:private summarize-workflows-in-workload
  "Summarize the workflows in `workload`."
  [workload]
  (let [workflows (try-to-get-workflows 3 workload)
        statuses  (set (keep :status workflows))]
    {:count     (count statuses)
     :statuses  statuses
     :workload  workload
     :workflows workflows}))

(deftest ^:parallel test-workflows-by-filters
  (testing "Get workflows by status"
    (let [{:keys [statuses workload]}
          (->> (endpoints/get-workloads)
               (filter :finished)
               (take 7)                 ; Why not?
               (map summarize-workflows-in-workload)
               (sort-by :count >)
               first)]
      (letfn [(verify [status]
                (run! #(is (= status (:status %)))
                      (endpoints/get-workflows workload status)))]
        (if (seq statuses)
          (run! verify statuses)
          (testing "WARN: No workloads to test status query"
            (is (empty? statuses))))))))

(defn ^:private convert-to-bulk
  "Convert fileref `value` to BulkLoadFileModel in `bucket` for TDR."
  [value bucket]
  (let [basename (util/basename value)]
    {:description basename
     :mimeType (mime-type/ext-mime-type value)
     :sourcePath value
     :targetPath (str bucket basename)}))

(defn ^:private ingest-illumina-genotyping-array-inputs
  "Ingest inputs for the illumina_genotyping_array pipeline into the
   illumina_genotyping_array `dataset`"
  [dataset-id load-tag]
  (fixtures/with-temporary-cloud-storage-folder
    fixtures/gcs-tdr-test-bucket
    (fn [cloud-folder]
      (let [file (str cloud-folder "inputs.json")]
        (-> (resources/read-resource "illumina_genotyping_array/inputs.json")
            (assoc :ingested (workflows/tdr-now))
            (update :green_idat_cloud_path convert-to-bulk cloud-folder)
            (update :red_idat_cloud_path   convert-to-bulk cloud-folder)
            (json/write-str :escape-slash false)
            (gcs/upload-content file))
        (datarepo/poll-job
         (datarepo/ingest-table dataset-id file "inputs" load-tag))))))

;; FIXME: GH-1652 :kaocha/pending does not work with :parallel now.
#_
(deftest ^:parallel test-workload-sink-outputs-to-tdr
  (fixtures/with-fixtures
    [(fixtures/with-temporary-dataset
       (datasets/unique-dataset-request
        (env/getenv "WFL_TDR_DEFAULT_PROFILE")
        "illumina-genotyping-array.json"))
     (fixtures/with-shared-temporary-workspace-clone
       "wfl-dev/Illumina-Genotyping-Array-Template"
       "workflow-launcher-dev"
       [{:email (env/getenv "WFL_TDR_SERVICE_ACCOUNT")
         :accessLevel "OWNER"}])]
    (fn [[dataset-id workspace]]
      (let [dataset  (datarepo/datasets dataset-id)
            source   {:name                   "Terra DataRepo"
                      :dataset                dataset-id
                      :table                  "inputs"
                      :snapshotReaders        ["hornet@firecloud.org"]
                      :pollingIntervalMinutes 1
                      :loadTag                "loadTagToMonitor"}
            executor {:name                "Terra"
                      :workspace           workspace
                      :methodConfiguration "warp-pipelines/IlluminaGenotypingArray"
                      :fromSource          "importSnapshot"}
            sink     {:name        "Terra DataRepo"
                      :dataset     dataset-id
                      :table       "outputs"
                      :fromOutputs (resources/read-resource
                                    "illumina_genotyping_array/fromOutputs.edn")}
            workload (endpoints/exec-workload
                      (workloads/staged-workload-request source executor sink))]
        (try
          (ingest-illumina-genotyping-array-inputs dataset-id "ignoreThisRow")
          (ingest-illumina-genotyping-array-inputs dataset-id (:loadTag source))
          (let [row-ids      (-> dataset
                                 (datarepo/query-metadata-table
                                  (:table source) {} [:datarepo_row_id])
                                 :rows
                                 flatten)
                where-load   {:loadTag (:loadTag source)}
                keep-row-ids (-> dataset
                                 (datarepo/query-metadata-table
                                  (:table source) where-load [:datarepo_row_id])
                                 :rows
                                 flatten)
                [workflow & rest]
                (util/poll #(seq (endpoints/get-workflows workload)) 20 100)]
            (is (== 2 (count row-ids))
                "2 rows should have been ingested")
            (is (== 1 (count keep-row-ids))
                "1 row should have been ingested with our monitored load tag")
            (is workflow
                "One workflow should have been created")
            (is (= (first keep-row-ids) (:entity workflow))
                "Row ingested with monitored load tag should have been submitted")
            (is (empty? rest)
                "Only one workflow should have been created"))
          (finally
            (endpoints/stop-workload workload)))
        ;; Note: when the workload's workflows have finished,
        ;; we expect a notification for each workflow
        ;; to be emitted to the Slack channels in
        ;; `wfl.tools.workloads/watchers`.
        (is (util/poll
             #(-> workload :uuid endpoints/get-workload-status :finished)
             20 100)
            "The workload should have finished")
        (is (seq (:rows (datarepo/query-table dataset "outputs")))
            "outputs should have been written to the dataset")))))

(deftest ^:parallel test-logging-level
  (testing "the /logging_level endpoint works"
    (let [{:keys [level] :as init} (endpoints/get-logging-level)]
      (is (#'log/level-string? level))
      (try (let [other  (-> level set
                            (remove @#'log/levels)
                            first name str/upper-case)
                 posted (endpoints/post-logging-level other)
                 got    (endpoints/get-logging-level)]
             (is (= got posted {:level other})))
           (finally (endpoints/post-logging-level level)))
      (is (= init (endpoints/get-logging-level))))))

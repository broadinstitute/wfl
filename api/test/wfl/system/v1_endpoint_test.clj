(ns wfl.system.v1-endpoint-test
  (:require [clojure.test               :refer [deftest is testing]]
            [clojure.instant            :as instant]
            [clojure.set                :as set]
            [clojure.spec.alpha         :as s]
            [clojure.string             :as str]
            [wfl.api.handlers           :as handlers]
            [wfl.debug]
            [wfl.environment            :as env]
            [wfl.module.covid           :as module]
            [wfl.service.cromwell       :as cromwell]
            [wfl.service.datarepo       :as datarepo]
            [wfl.service.google.storage :as gcs]
            [wfl.tools.datasets         :as datasets]
            [wfl.tools.endpoints        :as endpoints]
            [wfl.tools.fixtures         :as fixtures]
            [wfl.tools.workloads        :as workloads]
            [wfl.tools.resources        :as resources]
            [wfl.util                   :as util]
            [clojure.data.json          :as json])
  (:import [clojure.lang ExceptionInfo]
           [java.time.format DateTimeFormatter]
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
      (is (= uuid (:uuid workload)))
      (is (:started workload))
      (let [workflows (endpoints/get-workflows workload)]
        (is (every? :updated workflows))
        (is (every? :uuid workflows)))
      (verify-internal-properties-removed workload)
      (workloads/when-all-workflows-finish verify-succeeded-workload workload))))

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
      (workloads/when-all-workflows-finish verify-succeeded-workload workload))))

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

(defn ^:private test-retry-workload
  [request]
  (let [workload (endpoints/create-workload request)
        bad-statuses (set/difference cromwell/status? cromwell/retry-status?)]
    (letfn [(check-message-and-throw [message status]
              (try
                (endpoints/retry-workflows workload status)
                (catch Exception cause
                  (is (= message (-> (ex-data cause) util/response-body-json :message))
                      (str "Unexpected or missing exception message for status "
                           status))
                  (throw cause))))
            (should-throw-400 [message status]
              (is (thrown-with-msg?
                   ExceptionInfo #"clj-http: status 400"
                   (check-message-and-throw message status))
                  (str "Expecting 400 error for retry with status " status)))]
      (testing "retry-workflows fails (400) when workflow status unsupported"
        (run! (partial should-throw-400
                       handlers/retry-unsupported-status-error-message)
              bad-statuses))
      (testing "retry-workflows fails (400) when no workflows for supported status"
        (run! (partial should-throw-400
                       handlers/retry-no-workflows-error-message)
              cromwell/retry-status?)))))

(deftest ^:parallel test-retry-wgs-workload
  (test-retry-workload (workloads/wgs-workload-request (UUID/randomUUID))))
(deftest ^:parallel test-retry-aou-workload
  (test-retry-workload (workloads/aou-workload-request (UUID/randomUUID))))
(deftest ^:parallel test-retry-xx-workload
  (test-retry-workload (workloads/xx-workload-request (UUID/randomUUID))))
(deftest ^:parallel test-retry-sg-workload
  (test-retry-workload (workloads/sg-workload-request (UUID/randomUUID))))

(deftest ^:parallel test-append-to-aou-workload
  (let [await    (partial cromwell/wait-for-workflow-complete
                          @workloads/cromwell-url)
        workload (endpoints/exec-workload
                  (workloads/aou-workload-request (UUID/randomUUID)))]
    (testing "appending sample successfully launches an aou workflow"
      (is (->> workload
               (endpoints/append-to-aou-workload [workloads/aou-sample])
               (map (comp await :uuid))
               (every? #{"Succeeded"})))
      (->> (endpoints/get-workload-status (:uuid workload))
           (workloads/when-all-workflows-finish verify-succeeded-workload)))))

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
        source    {:name            "Terra DataRepo"
                   :dataset         "79fc88f5-dcf4-48b0-8c01-615dfbc1c63a"
                   :table           "flowcells"
                   :column          "last_modified_date"
                   :snapshotReaders ["hornet@firecloud.org"]}
        executor  {:name                       "Terra"
                   :workspace                  workspace
                   :methodConfiguration        (terra-ns "sarscov2_illumina_full")
                   :methodConfigurationVersion 1
                   :fromSource                 "importSnapshot"}
        sink      {:name           "Terra Workspace"
                   :workspace      workspace
                   :entityType     "assemblies"
                   :identifier     "flowcell_id"
                   :fromOutputs    (resources/read-resource
                                    "sarscov2_illumina_full/entity-from-outputs.edn")
                   :skipValidation true}]
    (workloads/covid-workload-request source executor sink)))

(defn instantify-timestamps
  "Replace timestamps at keys KS of map M with parsed #inst values."
  [m & ks]
  (reduce (fn [m k] (update m k instant/read-instant-timestamp)) m ks))

(deftest ^:parallel test-covid-workload
  (testing "/create covid workload"
    (let [workload-request (covid-workload-request)
          {:keys [creator started uuid] :as workload}
          (-> workload-request
              endpoints/create-workload
              (update :created instant/read-instant-timestamp))]
      (is (s/valid? ::module/workload-request  workload-request))
      (is (s/valid? ::module/workload-response workload))
      (verify-internal-properties-removed workload)
      (is (not started))
      (is (= @workloads/email creator))
      (testing "/workload status"
        (let [{:keys [started] :as response}
              (-> uuid endpoints/get-workload-status
                  (instantify-timestamps :created))]
          (is (not started))
          (verify-internal-properties-removed response)
          (is (s/valid? ::module/workload-response response))))
      (testing "/workload all"
        (let [{:keys [started] :as response}
              (-> (endpoints/get-workloads)
                  (->> (filter (comp #{uuid} :uuid)))
                  first
                  (instantify-timestamps :created))]
          (is (not started))
          (verify-internal-properties-removed response)
          (is (s/valid? ::module/workload-response response))))
      (testing "/start covid workload"
        (let [{:keys [created started] :as response}
              (-> workload endpoints/start-workload
                  (instantify-timestamps :created :started))]
          (is (s/valid? ::module/workload-response response))
          (is (inst? created))
          (is (inst? started))))
      (testing "/stop covid workload"
        (let [{:keys [created started stopped] :as response}
              (-> workload endpoints/stop-workload
                  (instantify-timestamps :created :started :stopped))]
          (is (s/valid? ::module/workload-response response))
          (is (inst? created))
          (is (inst? started))
          (is (inst? stopped)))))))

(defn ^:private verify-workflows-by-status
  [workload status]
  (run! #(is (= status (:status %)))
        (endpoints/get-workflows workload status)))

(defn ^:private try-to-get-workflows
  "Try up to `n` times to summarize the workflows in `workload`."
  [n workload]
  (try (endpoints/get-workflows workload)
       (catch Throwable x
         (wfl.debug/trace x)
         (when (> n 0)
           (try-to-get-workflows (dec n) workload)))))

(defn ^:private summarize-workflows-in-workload
  "Summarize the workflows in `workload`."
  [workload]
  (let [workflows (try-to-get-workflows 3 workload)]
    {:count     (count workflows)
     :workload  workload
     :workflows workflows}))

(def uuids ["290ed0e7-0d35-4544-bac8-08c6d908c9cb"
            "320c5d0e-ebb6-4dbc-9499-e76f498e0223"
            "3d09aa15-4da8-4506-bbf3-18797d10b801"
            "4964de53-9917-4acb-a375-19ddf4924360"
            "4d4b0fc2-65b6-4226-aa42-5b07a040e78f"
            "57683ed9-dc1f-4c22-ad0a-25d5fd25ecb2"
            "58ecd7d0-08c4-4c18-8d58-0aa1bb868254"
            "5a4d0f8d-1d81-44d4-ac46-0c8195acfa0f"
            "6767ac9c-8935-4d62-b7a8-3819b970a1ed"
            "6a40bfb8-0759-47db-8f28-ad909d4364ec"
            "72b4a0d4-b5b2-4147-bd04-f8968c7733a8"
            "772ebb78-e4b3-4434-9689-317bcebf4084"
            "8423405a-2a1d-47a8-8710-48ea15ed6d1d"
            "917229c7-c870-45b6-8608-1e22417298b0"
            "d0ef3be1-4bd7-439a-8dec-7c2c21ffbc14"
            "daa8f078-944c-481b-a565-d96c98daf568"
            "dfba2358-4815-4778-ac1e-65d7e2de3513"
            "e7b11c59-6bb4-4c1b-be50-77282bda5c04"
            "ef7624aa-2d9b-439c-80b7-c45f028ad21d"
            "f626e9e4-0c9e-4389-a7c8-247b4dbc7952"
            "ff44670f-34df-4baf-b02a-b271fef04d32"])

(def interesting-uuid? (set uuids))

(comment
  (clojure.test/test-vars [#'test-workflows-by-status])
  (clojure.test/test-vars [#'test-create-wgs-workload])
  (json/read-str (slurp "./spec.json") :key-fn keyword)
  )

(deftest ^:parallel test-workflows-by-status
  (testing "Get workflows by status"
    (let [{:keys [workflows workload]}
          (->> (endpoints/get-workloads)
               wfl.debug/trace
               (filter (comp interesting-uuid? :uuid))
               wfl.debug/trace
               (map summarize-workflows-in-workload)
               (filter (comp :finished :workload))
               (sort-by :count >)
               first wfl.debug/trace :workflows)
          statuses (set (map :status workflows))]
      (is (seq statuses))
      (run! (partial verify-workflows-by-status workload) statuses))))

(def ^:private tdr-date-time-formatter
  "The Data Repo's time format."
  (DateTimeFormatter/ofPattern "YYYY-MM-dd'T'HH:mm:ss"))

(defn ^:private ingest-illumina-genotyping-array-inputs
  "Ingest illumina_genotyping_array pipeline inputs into `dataset`."
  [dataset]
  (fixtures/with-temporary-cloud-storage-folder
    fixtures/gcs-test-bucket
    (fn [temp]
      (let [file (str temp "inputs.json")]
        (-> "illumina_genotyping_array/inputs.json"
            resources/read-resource
            (assoc :ingested (.format (util/utc-now) tdr-date-time-formatter))
            (json/write-str :escape-slash false)
            (gcs/upload-content file))
        (datarepo/poll-job (datarepo/ingest-table dataset file "inputs"))))))

(deftest ^:parallel test-workload-sink-outputs-to-tdr
  (fixtures/with-fixtures
    [(fixtures/with-temporary-dataset
       (datasets/unique-dataset-request
        (env/getenv "WFL_TDR_DEFAULT_PROFILE")
        "illumina-genotyping-array.json"))
     (fixtures/with-temporary-workspace-clone
       "wfl-dev/Illumina-Genotyping-Array-Template"
       "workflow-launcher-dev")]
    (fn [[dataset workspace]]
      (let [source   {:name            "Terra DataRepo"
                      :dataset         dataset
                      :table           "inputs"
                      :column          "ingested"
                      :snapshotReaders ["hornet@firecloud.org"]}
            executor {:name                       "Terra"
                      :workspace                  workspace
                      :methodConfiguration        "warp-pipelines/IlluminaGenotypingArray"
                      :methodConfigurationVersion 1
                      :fromSource                 "importSnapshot"}
            sink     {:name        "Terra DataRepo"
                      :dataset     dataset
                      :table       "outputs"
                      :fromOutputs (resources/read-resource
                                    "illumina_genotyping_array/fromOutputs.edn")}
            workload (endpoints/exec-workload
                      (workloads/covid-workload-request source executor sink))]
        (try
          (ingest-illumina-genotyping-array-inputs dataset)
          (is (util/poll #(seq (endpoints/get-workflows workload)) 20 100)
              "a workflow should have been created")
          (finally
            (endpoints/stop-workload workload)))
        (is (util/poll
             #(-> workload :uuid endpoints/get-workload-status :finished)
             20 100)
            "The workload should have finished")
        (is (-> dataset (datarepo/query-table "outputs") seq)
            "outputs should have been written to the dataset")))))

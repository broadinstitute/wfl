(ns wfl.system.v1-endpoint-test
  (:require [clojure.test               :refer [deftest is testing]]
            [clojure.instant            :as instant]
            [clojure.set                :as set]
            [clojure.spec.alpha         :as s]
            [clojure.string             :as str]
            [wfl.module.covid           :as module]
            [wfl.service.cromwell       :as cromwell]
            [wfl.service.google.storage :as gcs]
            [wfl.tools.endpoints        :as endpoints]
            [wfl.tools.fixtures         :as fixtures]
            [wfl.tools.workloads        :as workloads]
            [wfl.tools.resources        :as resources]
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
  [request implemented?]
  (let [workload  (endpoints/exec-workload request)]
    (testing "retry-workflows fails (400) with unsupported workflow status"
      (is (thrown-with-msg?
           ExceptionInfo #"clj-http: status 400"
           (endpoints/retry-workflows workload "Succeeded"))))
    (when-not implemented?
      (testing "retry-workflows fails (501) when unimplemented for pipeline"
        (is (thrown-with-msg?
             ExceptionInfo #"clj-http: status 501"
             (endpoints/retry-workflows workload "Failed")))))))

(deftest ^:parallel test-retry-wgs-workload
  (test-retry-workload (workloads/wgs-workload-request (UUID/randomUUID)) false))
(deftest ^:parallel test-retry-aou-workload
  (test-retry-workload (workloads/aou-workload-request (UUID/randomUUID)) false))
(deftest ^:parallel test-retry-xx-workload
  (test-retry-workload (workloads/xx-workload-request (UUID/randomUUID)) false))
(deftest ^:parallel test-retry-sg-workload
  (test-retry-workload (workloads/sg-workload-request (UUID/randomUUID)) false))

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
  (run! #(is (= (:status %) status)) (endpoints/get-workflows workload status)))

(deftest ^:parallel test-workflows-by-status
  (testing "Get workflows by status"
    (let [workload (first (endpoints/get-workloads))
          workflows (endpoints/get-workflows workload)]
      (->> (map :status workflows)
           (distinct)
           (run! #(verify-workflows-by-status workload %))))))

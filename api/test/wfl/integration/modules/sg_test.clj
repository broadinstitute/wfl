(ns wfl.integration.modules.sg-test
  (:require [clojure.test :refer [deftest testing is] :as clj-test]
            [clojure.string :as str]
            [wfl.environments :as env]
            [wfl.module.batch :as batch]
            [wfl.service.cromwell :refer [wait-for-workflow-complete submit-workflows]]
            [wfl.tools.endpoints :as endpoints]
            [wfl.tools.fixtures :as fixtures]
            [wfl.tools.workloads :as workloads]
            [wfl.util :as util :refer [absent? make-options]]
            [wfl.module.sg :as sg])
  (:import (java.time OffsetDateTime)
           (java.util UUID)))

(clj-test/use-fixtures :once fixtures/temporary-postgresql-database)

(defn ^:private make-sg-workload-request []
  (-> (UUID/randomUUID)
      workloads/sg-workload-request
      (assoc :creator (:email @endpoints/userinfo))))

(defn mock-submit-workload [{:keys [workflows]} _ _ _ _ _]
  (let [now       (OffsetDateTime/now)
        do-submit #(assoc % :uuid (UUID/randomUUID)
                          :status "Submitted"
                          :updated now)]
    (map do-submit workflows)))

(deftest test-create-workload!
  (letfn [(verify-workflow [workflow]
            (is (absent? workflow :uuid))
            (is (absent? workflow :status))
            (is (absent? workflow :updated)))
          (go! [workload-request]
            (let [workload (workloads/create-workload! workload-request)]
              (is (:created workload))
              (is (absent? workload :started))
              (is (absent? workload :finished))
              (run! verify-workflow (:workflows workload))))]
    (testing "single-sample workload-request"
      (go! (make-sg-workload-request)))))

(deftest test-create-workload-with-common-inputs
  (let [common-inputs {:biobambam_bamtofastq.max_retries 2
                       :ref_pac "gs://fake-location/temp_references/gdc/GRCh38.d1.vd1.fa.pac"}]
    (letfn [(go! [inputs]
              (letfn [(value-equal? [key] (= (key common-inputs) (key inputs)))]
                (is (value-equal? :biobambam_bamtofastq.max_retries))
                (is (value-equal? :ref_pac))))]
      (run! (comp go! :inputs) (-> (make-sg-workload-request)
                                   (assoc-in [:common :inputs] common-inputs)
                                   workloads/create-workload!
                                   :workflows)))))

(deftest test-start-workload!
  (letfn [(go! [workflow]
            (is (:uuid workflow))
            (is (:status workflow))
            (is (:updated workflow)))]
    (with-redefs-fn {#'batch/submit-workload! mock-submit-workload}
      #(-> (make-sg-workload-request)
           workloads/create-workload!
           workloads/start-workload!
           (as-> workload
                 (is (:started workload))
             (run! go! (:workflows workload)))))))

(deftest test-hidden-inputs
  (testing "google_account_vault_path and vault_token_path are not in inputs"
    (letfn [(go! [inputs]
              (is (absent? inputs :vault_token_path))
              (is (absent? inputs :google_account_vault_path)))]
      (->> (make-sg-workload-request)
           workloads/create-workload!
           :workflows
           (run! (comp go! :inputs))))))

(deftest test-create-empty-workload
  (let [workload (->> {:executor (get-in env/stuff [:wgs-dev :cromwell :url])
                       :output   "gs://broad-gotc-dev-wfl-ptc-test-outputs/sg-test-output/"
                       :pipeline sg/pipeline
                       :project  (format "(Test) %s" @workloads/git-branch)
                       :creator  (:email @endpoints/userinfo)}
                      workloads/execute-workload!
                      workloads/update-workload!)]
    (is (:finished workload))))

(deftest test-submitted-workflow-inputs
  (letfn [(prefixed? [prefix key] (str/starts-with? (str key) (str prefix)))
          (strip-prefix [[k v]]
            [(keyword (util/unprefix (str k) (str ":" sg/pipeline ".")))
             v])
          (verify-workflow-inputs [inputs]
            (is (:supports_common_inputs inputs))
            (is (:supports_inputs inputs))
            (is (:overwritten inputs)))
          (verify-submitted-inputs [_ _ _ inputs _ _]
            (map
             (fn [in]
               (is (every? #(prefixed? (keyword sg/pipeline) %) (keys in)))
               (verify-workflow-inputs (into {} (map strip-prefix in)))
               (UUID/randomUUID))
             inputs))]
    (with-redefs-fn {#'submit-workflows verify-submitted-inputs}
      (fn []
        (->
         (make-sg-workload-request)
         (assoc-in [:common :inputs]
                   {:supports_common_inputs true :overwritten false})
         (update :items
                 (partial map
                          #(update % :inputs
                                   (fn [xs] (merge xs {:supports_inputs true :overwritten true})))))
         workloads/execute-workload!)))))

(deftest test-workflow-options
  (letfn [(verify-workflow-options [options]
            (is (:supports_common_options options))
            (is (:supports_options options))
            (is (:overwritten options)))
          (verify-submitted-options [env _ _ inputs options _]
            (let [defaults (util/make-options env)]
              (verify-workflow-options options)
              (is (= defaults (select-keys options (keys defaults))))
              (map (fn [_] (UUID/randomUUID)) inputs)))]
    (with-redefs-fn {#'submit-workflows verify-submitted-options}
      (fn []
        (->
         (make-sg-workload-request)
         (assoc-in [:common :options]
                   {:supports_common_options true :overwritten false})
         (update :items
                 (partial map
                          #(assoc % :options {:supports_options true :overwritten true})))
         workloads/execute-workload!
         :workflows
         (->> (map (comp verify-workflow-options :options))))))))

(deftest test-empty-workflow-options
  (letfn [(go! [workflow] (is (absent? workflow :options)))]
    (run! go! (-> (make-sg-workload-request)
                  (assoc-in [:common :options] {})
                  (update :items (partial map #(assoc % :options {})))
                  workloads/create-workload!
                  :workflows))))

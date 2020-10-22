(ns wfl.integration.modules.xx-test
  (:require [clojure.test :refer [deftest testing is] :as clj-test]
            [wfl.service.cromwell :refer [wait-for-workflow-complete]]
            [wfl.tools.endpoints :as endpoints]
            [wfl.tools.fixtures :as fixtures]
            [wfl.tools.workloads :as workloads]
            [wfl.module.xx :as xx]
            [wfl.util :refer [absent? on]]
            [clojure.string :as str])
  (:import (java.util UUID)
           (java.time OffsetDateTime)))

(clj-test/use-fixtures :once fixtures/clean-db-fixture)

(def exome-test-storage
  (str/join "/" ["gs://broad-gotc-dev-wfl-ptc-test-inputs"
                 "external-reprocessing"
                 "exome"
                 "develop"
                 ""]))

(defn- make-xx-workload-request []
  (-> (UUID/randomUUID)
    workloads/xx-workload-request
    (assoc :creator (:email @endpoints/userinfo))))

(defn mock-submit-workload [{:keys [workflows]}]
  (let [now       (OffsetDateTime/now)
        do-submit #(assoc % :uuid (UUID/randomUUID)
                            :status "Submitted"
                            :updated now)]
    (map do-submit workflows)))

(deftest test-populating-input-items
  (testing "create workload with google cloud storage url as `items`"
    (let [items (xx/normalize-input-items (str exome-test-storage "RP-929.NA12878/RP-929.NA12878.cram"))]
      (is (== 1 (count items)))
      (let [input (first items)]
        (is :input_cram input)
        (is (absent? input :input_bam))))
    (let [items (xx/normalize-input-items (str exome-test-storage "not-a-real.unmapped.bam"))]
      (is (== 1 (count items)))
      (let [input (first items)]
        (is :input_bam input)
        (is (absent? input :input_cram)))))
  (testing "`items` maps are un-nested'"
    (let [uuids (take 3 (repeatedly #(UUID/randomUUID)))
          items (map (fn [uuid] {:inputs {:some-input uuid}}) uuids)]
      (is (every? identity (map #(= %1 (:some-input %2)) uuids (xx/normalize-input-items items)))))))

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
      (go! (make-xx-workload-request)))
    (testing "make from bucket"
      (go! (assoc (make-xx-workload-request) :items exome-test-storage)))))

(deftest test-create-workload-with-common-inputs
  (let [common-inputs {:bait_set_name      "Geoff"
                       :bait_interval_list "gs://fake-input-bucket/interval-list"}]
    (letfn [(go! [inputs]
              (letfn [(value-equal? [key] (partial on = key common-inputs inputs))]
                (is (value-equal? :bait_set_name))
                (is (value-equal? :bait_interval_list))))]
      (run! (comp go! :inputs) (-> (make-xx-workload-request)
                                 (assoc :common_inputs common-inputs)
                                 workloads/create-workload!
                                 :workflows)))))

(deftest test-start-workload!
  (letfn [(go! [workflow]
            (is (:uuid workflow))
            (is (:status workflow))
            (is (:updated workflow)))]
    (with-redefs-fn {#'xx/submit-workload! mock-submit-workload}
      #(-> (make-xx-workload-request)
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
      (->> (make-xx-workload-request)
        workloads/create-workload!
        :workflows
        (run! (comp go! :inputs))))))

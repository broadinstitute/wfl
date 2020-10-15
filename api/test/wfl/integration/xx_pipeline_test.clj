(ns wfl.integration.xx-pipeline-test
  (:require [clojure.test :refer [deftest testing is] :as clj-test]
            [wfl.service.cromwell :refer [wait-for-workflow-complete]]
            [wfl.tools.endpoints :as endpoints]
            [wfl.tools.fixtures :as fixtures]
            [wfl.tools.workloads :as workloads]
            [wfl.module.xx :as xx]
            [wfl.service.postgres :as postgres]
            [wfl.jdbc :as jdbc])
  (:import (java.util UUID)
           (java.time OffsetDateTime)))

(clj-test/use-fixtures :once fixtures/clean-db-fixture)

(defmacro is-not
  ([form] `(is-not ~form nil))
  ([form msg] `(is (not ~form) ~msg)))

(defn- make-xx-workload-request [id]
  (->
    (workloads/xx-workload-request id)
    (assoc :creator (:email @endpoints/userinfo))))

(defn create-workload! [workload-request]
  (jdbc/with-db-transaction [tx (postgres/wfl-db-config)]
    (wfl.api.workloads/create-workload! tx workload-request)))

(defn start-workload! [workload]
  (jdbc/with-db-transaction [tx (postgres/wfl-db-config)]
    (wfl.api.workloads/start-workload! tx workload)))

(defn mock-submit-workload [{:keys [workflows]}]
  (let [now        (OffsetDateTime/now)
        do-submit! #(assoc % :uuid (UUID/randomUUID)
                             :status "Submitted"
                             :updated now)]
    (map do-submit! workflows)))

(deftest test-populating-input-items
  (testing "create workload with google cloud storage url as `items`"
    (let [items (xx/normalize-input-items "gs://broad-gotc-test-storage/single_sample/load_50/truth/master/NWD101908.cram")]
      (is (= 1 (count items)))
      (let [input (first items)]
        (is :input_cram input)
        (is-not (:input_bam input))))
    (let [items (xx/normalize-input-items "gs://broad-gotc-test-storage/single_sample/full/bams/HJYFJCCXX.4.Pond-492100.unmapped.bam")]
      (is (= 1 (count items)))
      (let [input (first items)]
        (is :input_bam input)
        (is-not (:input_cram input)))))
  (testing "supplying `items` maps is identity"
    (let [items {:fake (UUID/randomUUID)}]
      (is (= items (xx/normalize-input-items items))))))

(deftest test-create-workload!
  (letfn [(verify-workflow [workflow]
            (is-not (:uuid workflow))
            (is-not (:status workflow))
            (is-not (:updated workflow)))
          (go! [workload-request]
            (let [workload (create-workload! workload-request)]
              (do
                (is (:created workload))
                (is-not (:started workload))
                (is-not (:finished workload)))
              (run! verify-workflow (:workflows workload))))]
    (testing "single-sample workload-request"
      (go! (make-xx-workload-request (UUID/randomUUID))))
    (testing "make from bucket"
      (->
        (make-xx-workload-request (UUID/randomUUID))
        (assoc :items "gs://broad-gotc-test-storage/single_sample/load_50/truth/master/NWD101908.cram")
        go!))))

(deftest test-create-workload-with-common-inputs
  (let [common-inputs    {:bait_set_name      "Geoff"
                          :bait_interval_list "gs://fake-input-bucket/interval-list"}
        workload-request (->
                           (UUID/randomUUID)
                           (make-xx-workload-request)
                           (assoc :common_inputs common-inputs))
        workload         (create-workload! workload-request)]
    (letfn [(go [inputs]
              (letfn [(value-equal? [key] (= (key common-inputs) (key inputs)))]
                (do
                  (is (value-equal? :bait_set_name))
                  (is (value-equal? :bait_interval_list)))))]
      (run! (comp go :inputs) (:workflows workload)))))

(deftest test-start-workload!
  (with-redefs-fn {#'xx/submit-workload! mock-submit-workload}
    #(let [workload (->>
                      (make-xx-workload-request (UUID/randomUUID))
                      create-workload!
                      start-workload!)]
       (letfn [(go! [workflow]
                 (do
                   (is (:uuid workflow))
                   (is (:status workflow))
                   (is (:updated workflow))))]
         (run! go! (:workflows workload))))))


(deftest test-submit-workload!
  (let [workload (create-workload! (make-xx-workload-request (UUID/randomUUID)))]
    (run! #(is (:uuid %)) (xx/submit-workload! workload))))

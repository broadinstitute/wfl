(ns zero.test.stub-module-test
  (:require [clojure.test :refer [deftest testing is use-fixtures]]
            [zero.api.handlers :refer [post-create
                                       post-start
                                       get-workload
                                       add-workload!
                                       start-workload!]]
            [zero.service.cromwell :as cromwell]
            [zero.test.tools.endpoint-helpers :as testtools]
            [zero.test.tools.stub-module :as stub]
            [zero.test.tools.fixtures :refer [method-overload-fixture]]))

(use-fixtures :once
  (method-overload-fixture add-workload! stub/pipeline stub/add-workload!)
  (method-overload-fixture start-workload! stub/pipeline stub/start-workload!))

(defn- mk-stub-workload []
  "`post-create` a stub workload"
  (post-create {:parameters {:body testtools/stub-workload}}))

(def ok?
  "Test the message status for HTTP 400"
  (comp (partial = 200) :status))

(defn- query-workload [uuid]
  (let [assert-ok (fn [response]
                    (if (not (ok? response))
                      (throw (ex-info "Failed to get workload status"
                               {:status (:status response)}))
                      response))
        request   {:parameters {:body [{:uuid uuid}]}}]
    ((comp first :body assert-ok get-workload) request)))

(deftest test-create-stub-workload
  (testing "`post-create` a stub workload"
    (let [response (mk-stub-workload)]
      (is (ok? response))
      (let [{:keys [pipeline uuid]} (:body response)]
        (is (= pipeline stub/pipeline))
        (is uuid)))))

(deftest test-get-stub-workload
  (testing "Test that the stub was correctly cached in the database"
    (let [uuid     (get-in (mk-stub-workload) [:body :uuid])
          response (query-workload uuid)]
      (is (= stub/pipeline (:pipeline response)))
      (is (not (:started response))))))

(deftest test-start-sub-workload
  (testing "`post-start` a stub workload"
    (let [workload (:body (mk-stub-workload))
          response (post-start {:parameters {:body [workload]}})]
      (is (ok? response))
      (let [update (query-workload (:uuid workload))]
        (is (:started update))))))

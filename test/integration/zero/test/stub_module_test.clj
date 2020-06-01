(ns zero.test.stub-module-test
  (:require [clojure.test :refer [deftest testing is use-fixtures]]
            [clojure.string :as str]
            [zero.api.handlers :refer [post-create add-workload!]]
            [zero.service.cromwell :as cromwell]
            [zero.test.tools.endpoint-helpers :as testtools]
            [zero.test.tools.stub-module :as stub]
            [zero.test.tools.fixtures :refer [method-overload-fixture]]))

(use-fixtures :once
  (method-overload-fixture add-workload! stub/pipeline stub/add-workload!))

(deftest test-create-workload
  (testing "creating a stub workload"
    (let [response (post-create {:parameters {:body testtools/stub-workload}})]
      (is (= 200 (:status response))))))

(ns zero.unit.all-modules-test
  (:require [clojure.test :refer [deftest testing is]]
            [zero.module.all :as all]))

(deftest test-get-cromwell-environment
  (let [url (get-in zero.environments/gotc-dev [:cromwell :url])]
    (testing "get a cromwell env from all possible envs"
      (is (= :gotc-dev (all/get-cromwell-environment url))))
    (testing "get a cromwell env from a set of possible envs"
      (let [allowable-envs #{:wgs-dev :wgs-prod :wgs-staging}]
        (is (= :wgs-dev (all/get-cromwell-environment allowable-envs url)))))
    (testing "unknown url"
      (is (nil? (all/get-cromwell-environment "https://no.such.cromwell/"))))))

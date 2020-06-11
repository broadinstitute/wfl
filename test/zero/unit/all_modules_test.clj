(ns zero.unit.all-modules-test
  (:require [clojure.test :refer [deftest testing is]]
            [zero.environments :as env]
            [zero.module.all :as all]))

(deftest test-cromwell-environments
  (let [url (get-in env/gotc-dev [:cromwell :url])]
    (testing "get a cromwell env from all possible envs"
      (is (some #{:gotc-dev} (all/cromwell-environments url))))
    (testing "get a cromwell env from a set of possible envs"
      (is (some #{:wgs-dev} (all/cromwell-environments
                              #{:wgs-dev :wgs-prod :wgs-staging} url))))
    (testing "unknown url"
      (is (nil? (all/cromwell-environments "https://no.such.cromwell/"))))))

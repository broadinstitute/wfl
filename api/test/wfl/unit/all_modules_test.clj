(ns wfl.unit.all-modules-test
  (:require [clojure.test :refer [deftest testing is]]
            [wfl.environments :as env]
            [wfl.module.all :as all]
            [wfl.module.aou :as aou]
            [wfl.module.wgs :as wgs]
            [wfl.module.xx :as xx]
            [wfl.wfl :as wfl]
            [wfl.util :refer [basename]]))

(deftest test-cromwell-environments
  (let [url (get-in env/gotc-dev [:cromwell :url])]
    (testing "get a cromwell env from all possible envs"
      (is (some #{:gotc-dev} (all/cromwell-environments url))))
    (testing "get a cromwell env from a set of possible envs"
      (is (some #{:wgs-dev} (all/cromwell-environments
                             #{:wgs-dev :wgs-prod :wgs-staging} url))))
    (testing "unknown url"
      (is (nil? (all/cromwell-environments "https://no.such.cromwell/"))))))

(deftest test-version-edn-contains-wdl-version
  (let [edn          (wfl/get-the-version)
        has-version? #(= (:release %) (-> % :top basename edn))]
    (is (every? has-version? [aou/workflow-wdl
                              wgs/workflow-wdl
                              xx/workflow-wdl]))))

(ns wfl.unit.modules.all-test
  (:require [clojure.test :refer [deftest testing is]]
            [wfl.module.aou :as aou]
            [wfl.module.sg :as sg]
            [wfl.module.wgs :as wgs]
            [wfl.module.xx :as xx]
            [wfl.util :refer [leafname]]
            [wfl.wfl :as wfl]))

(deftest test-version-edn-contains-wdl-version
  (let [edn          (wfl/get-the-version)
        has-version? #(= (:release %) (-> % :path leafname edn))]
    (is (every? has-version? [aou/workflow-wdl
                              sg/workflow-wdl
                              wgs/workflow-wdl
                              xx/workflow-wdl]))))

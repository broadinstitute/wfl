(ns wfl.unit.modules.all-test
  (:require [clojure.test :refer [deftest testing is]]
            [wfl.module.aou :as aou]
            [wfl.module.sg :as sg]
            [wfl.module.wgs :as wgs]
            [wfl.module.xx :as xx]
            [wfl.util :refer [basename]]
            [wfl.wfl :as wfl]))

(deftest test-version-edn-contains-wdl-version
  (let [edn          (wfl/get-the-version)
        has-version? #(= (:release %) (-> % :path basename edn))]
    (is (every? has-version? [aou/workflow-wdl
                              #_sg/workflow-wdl ; Now a commit hash. =tbl
                              wgs/workflow-wdl
                              xx/workflow-wdl]))))

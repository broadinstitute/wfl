(ns wfl.unit.cromwell-test
  (:require [clojure.test :refer [deftest is]]
            [wfl.service.cromwell :as cromwell]))

(def a-wdl {:release "foo"
            :path   "bar/baz.wdl"})

(def b-wdl {:user    "user"
            :repo    "repo"
            :release "release"
            :path    "path"})

(deftest test-url-formatting
  (is (cromwell/wdl-map->url a-wdl)
      "https://cdn.jsdelivr.net/gh/broadinstitute/warp@foo/bar/baz.wdl")
  (is (cromwell/wdl-map->url b-wdl)
      "https://cdn.jsdelivr.net/gh/user/repo@release/path"))

(deftest test-wf-labels
  (is (= (select-keys (cromwell/make-workflow-labels a-wdl)
                      [:wfl-wdl :wfl-wdl-version])
         {:wfl-wdl "baz.wdl"
          :wfl-wdl-version "foo"}))
  (is (= (select-keys (cromwell/make-workflow-labels b-wdl)
                      [:wfl-wdl :wfl-wdl-version])
         {:wfl-wdl "path"
          :wfl-wdl-version "release"})))

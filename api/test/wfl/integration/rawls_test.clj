(ns wfl.integration.rawls-test
  (:require [clojure.test :refer [deftest is testing]]
            [wfl.service.rawls :as rawls]
            [wfl.util :as util])
  (:import [clojure.lang ExceptionInfo]
           [java.util UUID]))

;; Of the form NAMESPACE/NAME
(def workspace "general-dev-billing-account/test-snapshots")

;; A known Terra Data Repository snapshot's ID...
(def snapshot-id "7cb392d8-949b-419d-b40b-d039617d2fc7")

;; And a known link to the above TDR snapshot in the workspace.
(def known-snapshot-link {:name "snapshot" :referenceId "2d15f9bd-ecb9-46b3-bb6c-f22e20235232"})

(deftest test-get-snapshot
  (let [snapshot (rawls/get-snapshot workspace (:referenceId known-snapshot-link))]
    (is (= "DATA_REPO_SNAPSHOT" (:referenceType snapshot)))
    (is (= snapshot-id (get-in snapshot [:reference :snapshot])))
    (is (= (:name known-snapshot-link) (:name snapshot)))))

(deftest test-create-snapshot-reference
  (testing "Creating snapshot reference with same name of existing throws 409 error"
    (is (thrown-with-msg? ExceptionInfo #"clj-http: status 409"
                          (rawls/create-snapshot-reference workspace
                                                           snapshot-id
                                                           (:name known-snapshot-link)))))
  (let [name (str (UUID/randomUUID))]
    (util/bracket
     #(rawls/create-snapshot-reference workspace snapshot-id name "wfl.rawls-test/test-create-snapshot")
     #(rawls/delete-snapshot workspace %)
     #(let [snapshot (rawls/get-snapshot workspace %)]
        (is (= "DATA_REPO_SNAPSHOT" (:referenceType snapshot)))
        (is (= snapshot-id (get-in snapshot [:reference :snapshot])))
        (is (= name (:name snapshot)))))))

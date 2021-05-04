(ns wfl.integration.modules.covid-test
  (:require [clojure.test :refer :all]
            [clojure.test :as clj-test]
            [wfl.tools.fixtures :as fixtures]
            [wfl.jdbc :as jdbc]
            [wfl.module.covid :as covid]
            [wfl.service.rawls :as rawls])
  (:import [clojure.lang ExceptionInfo]))

(clj-test/use-fixtures :once fixtures/temporary-postgresql-database)

(def workload {:id 1})

(def workspace "general-dev-billing-account/test-snapshots")
(def executor {:workspace workspace})

(def snapshot-id "7cb392d8-949b-419d-b40b-d039617d2fc7")
(def reference-id "2d15f9bd-ecb9-46b3-bb6c-f22e20235232")

;; Source and source details
(def source {:details (format "%s_%09d" "TerraDataRepoSourceDetails" 1)})
(def sd-base {:id 1})
(def sd-snapshot (assoc sd-base :snapshot_id snapshot-id))
(def sd-reference (assoc sd-snapshot :snapshot_reference_id reference-id))

(defn ^:private mock-rawls-snapshot-reference [& _]
  {:cloningInstructions "COPY_NOTHING",
   :description "test importing a snapshot into a workspace",
   :name "snapshot",
   :reference {:instanceName "terra", :snapshot snapshot-id},
   :referenceId reference-id,
   :referenceType "DATA_REPO_SNAPSHOT",
   :workspaceId "e9d053b9-d79f-40b7-b701-904bf542ec2d"})

(defn ^:private mock-throw [& _] (throw (ex-info "mocked throw" {})))

(deftest test-snapshot-imported
  (let [snapshot-imported? #'covid/snapshot-imported?]
    (testing "Missing snapshot, missing reference, or failed fetch returns false"
      (with-redefs-fn
        {#'rawls/get-snapshot-reference mock-throw}
        #(letfn [(go [sd] (is (false? (snapshot-imported? sd executor))))]
           (let [source-details [sd-base sd-snapshot sd-reference]]
             (run! go source-details)))))
    (testing "Snapshot, reference, and successful fetch returns true"
      (with-redefs-fn
        {#'rawls/get-snapshot-reference mock-rawls-snapshot-reference}
        #(is (true? (snapshot-imported? sd-reference executor)))))))

(deftest test-import-snapshot
  (let [import-snapshot! #'covid/import-snapshot!]
    #_(testing "Successful create writes to db"
        (jdbc/with-db-transaction [tx (fixtures/testing-db-config)]
          (with-redefs-fn
            {#'rawls/create-snapshot-reference mock-rawls-snapshot-reference}
            #(import-snapshot! tx workload source sd-snapshot executor))))
    (testing "Failed create throws"
      (jdbc/with-db-transaction [tx (fixtures/testing-db-config)]
        (with-redefs-fn
          {#'rawls/create-snapshot-reference mock-throw}
          #(is (thrown-with-msg?
                ExceptionInfo #"Rawls unable to create snapshot reference"
                (import-snapshot! tx
                                  workload
                                  source
                                  sd-snapshot
                                  executor))))))))

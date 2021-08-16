(ns wfl.integration.jdbc-test
  (:require [clojure.test         :refer [deftest is testing use-fixtures]]
            [wfl.jdbc             :as jdbc]
            [wfl.service.postgres :as postgres]
            [wfl.tools.fixtures   :as fixtures]))

(use-fixtures :once fixtures/temporary-postgresql-database)

(deftest test-jdbc-protocol-extensions
  (let [create "CREATE TABLE %s (id SERIAL, arr text[], nestarr text[][])"
        table "test_protocols"
        rows ["dog" "cat" "panda"]
        nested-rows [["cat" "dana"] ["dog" "bard"]]]
    (jdbc/with-db-transaction [tx (postgres/wfl-db-config)]
      (jdbc/db-do-commands tx [(format create table)])

      (testing "Insert supports native clojure vector and psql array"
        (let [insert-res (jdbc/insert! tx table {:id 1 :arr rows})]
          (is (= rows (:arr (first insert-res))))))

      (testing "Insert supports native clojure vector and psql nested array"
        (let [insert-res (jdbc/insert! tx table {:id 2 :nestarr nested-rows})]
          (is (= nested-rows (map read-string (:nestarr (first insert-res)))))))

      (testing "Query supports native clojure vector and psql array"
        (let [query "SELECT * FROM %s WHERE id = ?;"
              query-res-1 (jdbc/query tx [(format query table) 1])
              query-res-2 (jdbc/query tx [(format query table) 2])]
          (is (= rows (:arr (first query-res-1))))
          (is (= nested-rows (map read-string (:nestarr (first query-res-2))))))))))

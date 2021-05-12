(ns wfl.integration.jdbc-test
  (:require [clojure.test :refer :all :as clj-test]
            [clojure.test]
            [wfl.jdbc :as jdbc]
            [wfl.tools.fixtures :as fixtures]))

(clj-test/use-fixtures :once fixtures/temporary-postgresql-database)

(deftest test-jdbc-protocol-extensions
  (let [create "CREATE TABLE %s (id SERIAL, arr text[])"
        table "test_protocols"
        rows ["dog" "cat" "panda"]]
    (jdbc/with-db-transaction [tx (fixtures/testing-db-config)]
      (jdbc/db-do-commands tx [(format create table)])

      (testing "Insert supports native clojure vector and psql array"
        (let [insert-res (jdbc/insert! tx table {:id 1 :arr rows})]
          (is (= rows (:arr (first insert-res))))))

      (testing "Query supports native clojure vector and psql array"
        (let [query "SELECT * FROM %s;"
              query-res (jdbc/query tx (format query table))]
          (is (= rows (:arr (first query-res)))))))))

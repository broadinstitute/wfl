(ns wfl.unit.routes-test
  (:require [clojure.test :refer [deftest is testing]]
            [wfl.api.routes :as routes]))

(def sample-endpoints
  [["/some/unauth/endpoint"
    {:get {:handler identity}}]
   ["/api/some/auth/endpoint"
    {:get {:handler identity}}]
   ["/api/some/other/auth/endpoint"
    {:get {:handler identity}}
    {:post {:handler identity}}]
   ["/api/has/swagger/config"
    {:get {:handler identity
           :swagger {:foo "bar"}}}]])

(defn config-for-endpoint
  "Return the config for the first endpoint matching the given route."
  [endpoint-route endpoints]
  (first (filter #(= endpoint-route (first %)) endpoints)))

(deftest test-route-processing
  (testing "route processing"
    (let [processed-sample-endpoints (routes/endpoint-swagger-auth-processor sample-endpoints)]
      (testing "no extra insertions"
        (is (not (contains?
                   (:get (second (config-for-endpoint "/some/unauth/endpoint" processed-sample-endpoints)))
                   :swagger))))
      (testing "proper insertion"
        (is (contains?
              (:get (second (config-for-endpoint "/api/some/auth/endpoint" processed-sample-endpoints)))
              :swagger))
        (is (every? #(contains? (first (vals %)) :swagger)
                    (rest (config-for-endpoint "/api/some/other/auth/endpoint" processed-sample-endpoints)))))
      (testing "proper contents"
        (is (= "Authenticated" (-> (config-for-endpoint "/api/some/auth/endpoint" processed-sample-endpoints)
                                   second
                                   :get
                                   :swagger
                                   :tags
                                   first)))
        (is (contains? (-> (config-for-endpoint "/api/some/auth/endpoint" processed-sample-endpoints)
                           second
                           :get
                           :swagger
                           :security
                           first) :googleoauth )))
      (testing "merges with existing"
        (is (= "Authenticated" (-> (config-for-endpoint "/api/has/swagger/config" processed-sample-endpoints)
                                   second
                                   :get
                                   :swagger
                                   :tags
                                   first)))
        (is (= "bar" (-> (config-for-endpoint "/api/has/swagger/config" processed-sample-endpoints)
                         second
                         :get
                         :swagger
                         :foo)))))))

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

(defn recursive-merge
  "Deep merging of maps so we can test that we don't lose anything in processing.

  Partially based on Marin Atanasov Nikolov's tutorial at
  http://dnaeon.github.io/recursively-merging-maps-in-clojure/"
  [& maps]
  (letfn [(partial-merge [& to-merge]
            (if (some #(map? %) to-merge)
              (apply merge-with partial-merge to-merge)
              (last to-merge)))]
    (reduce partial-merge maps)))

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
                           first) :googleoauth)))
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
                         :foo))))
      (testing "isn't lossy"
        (is (every? identity
                    (map (fn [sample processed]
                           (and
                             (= (first sample) (first processed))
                             (every? identity
                                     ;; does processed not change when we
                                     ;; override it with the original?
                                     ;; (is processed a superset of original)
                                     (map #(= %2 (recursive-merge %2 %1))
                                          (rest sample) (rest processed)))))
                         sample-endpoints processed-sample-endpoints)))))))

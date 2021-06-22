(ns wfl.integration.modules.shared
  (:require [clojure.test        :refer [is]]
            [wfl.tools.workloads :as    workloads]
            [wfl.util            :refer [absent?]])
  (:import [wfl.util UserException]))

(defn ^:private verify-workload-states [workload states]
  (doseq [[key test?] states] (is (test? workload key)))
  workload)

(defn run-workload-state-transition-test! [workload-request]
  (-> workload-request
      workloads/create-workload!
      (verify-workload-states {:created  contains?
                               :started  absent?
                               :stopped  absent?
                               :finished absent?})
      workloads/update-workload!
      (verify-workload-states {:created  contains?
                               :started  absent?
                               :stopped  absent?
                               :finished absent?})
      workloads/start-workload!
      (verify-workload-states {:created  contains?
                               :started  contains?
                               :stopped  absent?
                               :finished absent?})
      workloads/stop-workload!
      (verify-workload-states {:created  contains?
                               :started  contains?
                               :stopped  contains?
                               :finished absent?})
      workloads/update-workload!
      (verify-workload-states {:created  contains?
                               :started  contains?
                               :stopped  contains?
                               :finished contains?})))

(defn run-stop-workload-state-transition-test! [workload-request]
  (let [workload (workloads/create-workload! workload-request)]
    (verify-workload-states
     workload
     {:created  contains?
      :started  absent?
      :stopped  absent?
      :finished absent?})
    (is (thrown-with-msg?
         UserException #"Cannot stop workload"
         (workloads/stop-workload! workload)))))

(defn run-retry-is-not-supported-test! [workload-request]
  (let [workload (workloads/execute-workload! workload-request)
        failed   (workloads/workflows workload "Failed")]
    (is (thrown-with-msg?
         UserException #"Cannot retry workflows"
         (workloads/retry workload failed)))))

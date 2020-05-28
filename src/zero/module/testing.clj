(ns zero.module.testing
  "A dummy module for mocking a cromwell instance.")

(def pipeline "testing")

(defn update-workload!
  "Use transaction TX to update WORKLOAD statuses."
  [tx workload])

(def add-workload!
  "Add the workload described by BODY to the database DB."
  (fn [_ x] x))

(def start-workload!
  "Start the WORKLOAD in the database DB."
  (fn [_ x] x))

(ns zero.module.testing
  "A dummy module for mocking a cromwell instance.")

(def pipeline "TestPipeline")

(defn update-workload!
  "Use transaction TX to update WORKLOAD statuses."
  [tx workload])

(defn add-workload!
  "Add the workload described by BODY to the database DB."
  [tx body]
  (->> body
    first
    (filter second)
    (into {})))

(defn start-workload!
  "Start the WORKLOAD in the database DB."
  [tx workload]
  (->> workload
    (filter second)
    (into {})))

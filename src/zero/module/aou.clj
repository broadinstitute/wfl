(ns zero.module.aou
  "Process Arrays for the All Of Us project.")

(defn add-workload!
  "Add the workload described by BODY to the database DB."
  [db body]
  (->> body
       first
       (filter second)
       (into {})))

(defn start-workload!
  "Start the WORKLOAD in the database DB."
  [db workload]
  (->> workload
       (filter second)
       (into {})))

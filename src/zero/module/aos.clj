(ns zero.module.aos
  "Process Arrays for the All Of Us project.")

(defn add-workload!
  "Add the workload described by BODY to the database DB."
  [db body]
  (->> body
       first
       (filter second)
       (into {})))

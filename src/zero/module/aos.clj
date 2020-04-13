(ns zero.module.aos
  "Process Arrays for the All Of Us project."
  (:require [zero.util :as util]))

(defn create-workload
  "Remember the workload specified by BODY."
  [body]
  (let [environment (keyword (util/getenv "ENVIRONMENT" "debug"))]
    (->> body
         first
         (filter second)
         (into {}))))

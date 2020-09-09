(ns wfl.debug
  "Debug tools not specific to this program."
  (:require [clojure.pprint :refer [pprint]]))

(defmacro dump
  "Dump [EXPRESSION VALUE] where VALUE is EXPRESSION's value."
  [expression]
  `(let [x# ~expression]
     (do
       (pprint ['~expression x#])
       x#)))

(defmacro trace
  "Like DUMP but include location metadata."
  [expression]
  (let [{:keys [line column]} (meta &form)]
    `(let [x# ~expression]
       (do
         (pprint {:column ~column :file ~*file* :line ~line '~expression x#})
         x#))))

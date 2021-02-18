(ns wfl.tools.workflows
  (:require [clojure.edn :as edn]
            [clojure.set :as set]))

(defn make-object-type
  "Collect `parameters` description into an \"Object\" type."
  [parameters]
  (->> parameters
       (map #(set/rename-keys % {:name :fieldName :valueType :fieldType}))
       (assoc {:typeName "Object"} :objectFieldNames)))

(defn read-resource
  "Read the EDN file `name` relative to `test/resources/workflows/`, omitting
   the \".edn\" extension from `name`.
   Example
   -------
   (read-resource \"assemble-refbased-description\")"
  [name]
  (edn/read-string
   (slurp (str "test/resources/workflows/" name ".edn"))))

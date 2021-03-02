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

(defn traverse
  "Traverse the workflow `type`, calling `f` on the values with the `typeName`
   and `value` of non-traversable types."
  [f type object]
  (letfn [(make-type-environment [{:keys [objectFieldNames]}]
            (into {} (for [{:keys [fieldName fieldType]} objectFieldNames]
                       [(keyword fieldName) fieldType])))]
    ((fn go [type value]
       (case (:typeName type)
         "Array"
         (let [array-type (:arrayType type)]
           (map #(go array-type %) value))
         "Object"
         (let [name->type (make-type-environment type)]
           (into {} (map (fn [[k v]] [k (go (name->type k) v)]) value)))
         "Optional"
         (when value (go (:optionalType type) value))
         (f (:typeName type) value)))
     type object)))

(defn get-files [type value]
  "Return the unique set of objects in `value` of WDL type `File`."
  (letfn [(f [type object] (if (= "File" type) [object] []))]
    (set (flatten (vals (traverse f type value))))))

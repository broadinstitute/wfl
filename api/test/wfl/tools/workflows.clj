(ns wfl.tools.workflows
  (:require [clojure.edn :as edn]
            [clojure.set :as set]))

(defn make-object-type
  "Collect `parameters` description into an \"Object\" type."
  [parameters]
  (->> parameters
       (map #(set/rename-keys % {:name :fieldName :valueType :fieldType}))
       (assoc {:typeName "Object"} :objectFieldTypes)))

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
  "Use the workflow `type` to traverse the `object`, calling `f` on the values
   with primitive types."
  [f type object]
  (letfn [(make-type-environment [{:keys [objectFieldTypes]}]
            (into {} (for [{:keys [fieldName fieldType]} objectFieldTypes]
                       [(keyword fieldName) fieldType])))]
    ((fn go [type value]
       (case (:typeName type)
         "Array"
         (let [array-type (:arrayType type)]
           (map #(go array-type %) value))
         "Map"
         (let [{:keys [keyType valueType]} (:mapType type)]
           (into {} (map (fn [[k v]] [(go keyType k) (go valueType v)]) value)))
         "Object"
         (let [name->type (make-type-environment type)]
           (into {} (map (fn [[k v]] [k (go (name->type k) v)]) value)))
         "Optional"
         (when value (go (:optionalType type) value))
         "Pair"
         (let [ts (mapv (:pairType type) [:leftType :rightType])]
           (map go ts value))
         (f (:typeName type) value)))
     type object)))

(defn foldl
  "Use the workflow `type` to left-fold the `object`, calling `f` on the values
   with primitive types with the current state."
  [f init type object]
  (letfn [(make-type-environment [{:keys [objectFieldTypes]}]
            (into {} (for [{:keys [fieldName fieldType]} objectFieldTypes]
                       [(keyword fieldName) fieldType])))]
    ((fn go [state type value]
       (case (:typeName type)
         "Array"
         (let [array-type (:arrayType type)]
           (reduce #(go %1 array-type %2) state value))
         "Map"
         (let [{:keys [keyType valueType]} (:mapType type)]
           (reduce-kv #(-> (go %1 keyType %2) (go valueType %3)) state value))
         "Object"
         (let [name->type (make-type-environment type)]
           (reduce-kv #(go %1 (name->type %2) %3) state value))
         "Optional"
         (if value (go state (:optionalType type) value) state)
         "Pair"
         (let [{:keys [leftType rightType]} (:pairType type)]
           (-> (go state leftType  (first value))
               (go       rightType (second value))))
         (f state (:typeName type) value)))
     init type object)))

(defn get-files [type value]
  "Return the unique set of objects in `value` of WDL type `File`."
  (letfn [(f [files type object] (if (= "File" type) (cons object files) files))]
    (set (foldl f [] type value))))

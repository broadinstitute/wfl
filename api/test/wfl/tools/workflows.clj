(ns wfl.tools.workflows
  (:require [clojure.set :as set]))

(defn make-object-type
  "Collect `parameters` description into an \"Object\" type."
  [parameters]
  (->> parameters
       (map #(set/rename-keys % {:name :fieldName :valueType :fieldType}))
       (assoc {:typeName "Object"} :objectFieldTypes)))

(defn ^:private make-type-environment [{:keys [objectFieldTypes] :as _object}]
  (let [name->type (into {}
                         (for [{:keys [fieldName fieldType]} objectFieldTypes]
                           [(keyword fieldName) fieldType]))]
    (fn [varname]
      (or (name->type varname)
          (throw (ex-info "No type definition found for name."
                          {:name        (when varname (name varname))
                           :environment name->type}))))))

(defn traverse
  "Use the workflow `type` to traverse the `object`, calling `f` on the values
   with primitive types."
  [f type object]
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
   type object))

(defn foldl
  "Use ternary function `f` to reduce `object` tree of `type` starting
  from `init` while preserving the types of intermediate reductions."
  [f init type object]
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
         (-> state
             (go leftType  (first value))
             (go rightType (second value))))
       (f state (:typeName type) value)))
   init type object))

(defn get-files
  "Return the unique set of objects in `value` of WDL type `File`."
  [type value]
  (letfn [(f [files type object] (if (= "File" type) (conj files object) files))]
    (foldl f #{} type value)))

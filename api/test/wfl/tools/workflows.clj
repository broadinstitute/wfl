(ns wfl.tools.workflows
  (:require [clojure.set                :as set]
            [clojure.string             :as str]
            [wfl.mime-type              :as mime-type]
            [wfl.util                   :as util])
  (:import [java.time.format DateTimeFormatter]))

(defn make-object-type
  "Transform WDL inputs or outputs `description` into a `type` for use with
   `transform` and `foldl`."
  [description]
  (->> description
       (mapv #(set/rename-keys % {:name :fieldName :valueType :fieldType}))
       (assoc {:typeName "Object"} :objectFieldTypes)))

(defn ^:private make-type-environment [{:keys [objectFieldTypes] :as _type}]
  (let [collect    (juxt (comp keyword :fieldName) :fieldType)
        name->type (into {} (map collect objectFieldTypes))]
    (fn [varname]
      (or (name->type varname)
          (throw (ex-info "No type definition found for name."
                          {:name        (when varname (name varname))
                           :environment name->type}))))))

(defn traverse
  "Use the WDL `type` to traverse the `object`, calling `f` on the
   primitive objects in the object."
  [f [type object]]
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
       (f [(:typeName type) value])))
   type object))

(defn foldl
  "Reduce the WDL [type value] `_object` starting from `init` while preserving
   the types of intermediate reductions."
  [f init [type value :as _object]]
  ((fn go [state type value]
     (case (:typeName type)
       "Array"
       (let [array-type (:arrayType type)]
         (reduce #(go %1 array-type %2) state value))
       "Map"
       (let [{:keys [keyType valueType]} (:mapType type)]
         (reduce-kv #(-> %1 (go keyType %2) (go valueType %3)) state value))
       "Object"
       (let [name->type (make-type-environment type)]
         (reduce-kv #(go %1 (name->type %2) %3) state value))
       "Optional"
       (if value (go state (:optionalType type) value) state)
       "Pair"
       (let [{:keys [leftType rightType]} (:pairType type)
             [leftValue rightValue]       value]
         (-> state (go leftType leftValue) (go rightType rightValue)))
       (f state [(:typeName type) value])))
   init type value))

(defn get-files
  "Return the unique set of objects in `value` of WDL type `File`."
  [[_type _value :as object]]
  (letfn [(f [files [type value]] (case type
                                    "File" (conj files value)
                                    files))]
    (foldl f #{} object)))

(defn convert-to-bulk
  "Convert fileref column to BulkLoadFileModel for TDR"
  [value bucket]
  (let [basename (util/basename value)]
    {:description basename
     :mimeType (mime-type/ext-mime-type value)
     :sourcePath value
     :targetPath (str/join "/" [bucket basename])}))

(def tdr-date-time-formatter
  "The Data Repo's time format."
  (DateTimeFormatter/ofPattern "YYYY-MM-dd'T'HH:mm:ss"))

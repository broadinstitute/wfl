(ns wfl.protocols
  (:import [java.sql Array PreparedStatement]
           (clojure.lang IPersistentVector)))

;; extend protocol so psql insertion supports
;; clojure native vectors
;; https://stackoverflow.com/questions/22959804/inserting-postgresql-arrays-with-clojure
(extend-protocol clojure.java.jdbc/ISQLParameter
  IPersistentVector
  (set-parameter [v ^PreparedStatement stmt ^long i]
    (let [conn (.getConnection stmt)
          meta (.getParameterMetaData stmt)
          type-name (.getParameterTypeName meta i)]
      (if-let [elem-type (when (= (first type-name) \_) (apply str (rest type-name)))]
        (.setObject stmt i (.createArrayOf conn elem-type (to-array v)))
        (.setObject stmt i v)))))

;; extend protocol so psql arrays will be returned
;; as clojure native vectors
(extend-protocol clojure.java.jdbc/IResultSetReadColumn
  Array
  (result-set-read-column [val _ _]
    (into [] (.getArray val))))

(ns wfl.tools.resources
  (:require [clojure.java.io          :as io]
            [clojure.string           :as str]
            [clojure.tools.reader.edn :as edn]
            [wfl.util                 :as util])
  (:import [java.io FileNotFoundException]))

(defn read-resource
  "Read the uniquely named resource by suffix `suffix`.
   Example
   -------
   (read-resource \"assemble_refbased.edn\")"
  [suffix]
  (let [files (mapcat
               (fn [folder]
                 (->> (file-seq (io/file folder))
                      (map #(.getCanonicalPath %))
                      (filter #(str/ends-with? % suffix))))
               ["resources"
                "test/resources"
                "../derived/api/resources"
                "../derived/api/test/resources"])]
    (cond
      (empty? files)      (throw (FileNotFoundException. (str "No such file " suffix)))
      (< 1 (count files)) (throw (IllegalArgumentException. (str "No unique file named " suffix)))
      :else               (let [contents (slurp (first files))]
                            (case (util/extension suffix)
                              "edn"  (edn/read-string contents)
                              "json" (util/parse-json contents)
                              contents)))))

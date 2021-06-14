(ns wfl.stage
  "An interface for operations on a queue-based pipeline processing stage,
  e.g. source, executor, or sink."
  (:require [wfl.util :as util])
  (:import [wfl.util UserException]))

(defmulti validate-or-throw
  "Validate the `request` request."
  (fn [request] (:name request)))

(defmethod validate-or-throw :default
  [{:keys [name] :as request}]
  (throw (UserException. "Invalid request - unknown name"
                         (util/make-map name request))))

(defmulti peek-queue
  "Peek the first object from the `queue`, if one exists."
  (fn [queue] (:type queue)))

(defmulti pop-queue!
  "Pop the first object from the `queue`. Throws if none exists."
  (fn [queue] (:type queue)))

(defmulti queue-length
  "Return the number of objects in the `queue`."
  (fn [queue] (:type queue)))

(defmulti done?
  "Test if the processing `stage` is complete and will not process any more data."
  (fn [stage] (:type stage)))

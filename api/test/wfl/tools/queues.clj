(ns wfl.tools.queues
  (:require [wfl.stage :as stage])
  (:import [java.util ArrayDeque Collection]))

(def ^:private testing-queue-type "TestQueue")

(defn make-queue-from-list [^Collection items]
  {:type testing-queue-type :queue (ArrayDeque. items)})

(defmethod stage/peek-queue testing-queue-type
  [q]
  (.peekFirst ^ArrayDeque (:queue q)))

(defmethod stage/pop-queue! testing-queue-type
  [q]
  (.removeFirst ^ArrayDeque (:queue q)))

(defmethod stage/queue-length testing-queue-type
  [q]
  (.size ^ArrayDeque (:queue q)))

(defmethod stage/done? testing-queue-type
  [q]
  (.isEmpty ^ArrayDeque (:queue q)))

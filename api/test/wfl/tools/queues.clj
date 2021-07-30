(ns wfl.tools.queues
  (:require [wfl.stage :as stage])
  (:import [java.util ArrayDeque]))

(def ^:private testing-queue-type "TestQueue")

(defn make-queue-from-list [items]
  {:type testing-queue-type :queue (ArrayDeque. items)})

(defmethod stage/peek-queue   testing-queue-type [q] (-> q :queue .peekFirst))
(defmethod stage/pop-queue!   testing-queue-type [q] (-> q :queue .removeFirst))
(defmethod stage/queue-length testing-queue-type [q] (-> q :queue .size))
(defmethod stage/done?        testing-queue-type [q] (-> q :queue .isEmpty))

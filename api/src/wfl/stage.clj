(ns wfl.stage
  "An interface for operations on a queue-based pipeline processing stage,
  e.g. source, executor, or sink.")

(defmulti peek-queue
  "Peek the first object from the `queue`, if one exists."
  :type)

(defmulti pop-queue!
  "Pop the first object from the `queue`. Throws if none exists."
  :type)

(defmulti queue-length
  "Return the number of objects in the `queue`."
  :type)

(defmulti done?
  "Test if the processing `stage` is complete and will not process any more data."
  :type)

(ns wfl.stage
  "Interface and methods for operations on a queue-based
  pipeline processing stage, e.g. source, executor, or sink."
  (:require [wfl.service.postgres :as postgres]
            [wfl.util             :as util])
  (:import [wfl.util UserException]))

(defmulti validate-or-throw
  "Validate the `request` request."
  :name)

(defmethod validate-or-throw :default
  [{:keys [name] :as request}]
  (throw (UserException. "Invalid request - unknown name"
                         (util/make-map name request))))

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

(defn log-prefix
  "Prefix string for `stage` logs indicating the `type` (table) and row `id`."
  [{:keys [type id] :as _stage}]
  (format "[%s id=%s]" type id))

(defn throw-if-no-details-table
  "Throw if `details` table does not exist."
  [tx {:keys [details type] :as _stage}]
  (when-not (postgres/table-exists? tx details)
    (throw (ex-info "Missing details table" {:type type :details details}))))

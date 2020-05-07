(ns zero.dx
  "Diagnose failed Cromwell calls and other problems."
  (:require [clojure.data :as data]
            [clojure.edn :as edn]
            [clojure.data.json :as json]
            [clojure.instant :as inst]
            [clojure.java.io :as io]
            [clojure.pprint :refer [pprint]]
            [clojure.string :as str]
            [clj-http.client :as http]
            [zero.service.cromwell :as cromwell]
            [zero.once :as once]
            [zero.util :as util]
            [zero.zero :as zero]))

(defn describe
  "Describe the COMMANDS."
  [commands]
  (-> [""
       "%1$s dx: tools to help debug workflow problems."
       ""
       "Usage: %1$s dx <tool> [<arg> ...]"
       "Where: <tool> is the name of some diagnostic tool."
       "       <arg> ... are optional arguments to <tool>."
       ""
       "The <tool>s and their <arg>s are named here."]
      (concat (util/summarize commands) [""])
      (->> (str/join \newline))
      (format zero/the-name)))

(defn flatten-workflow
  "Return all workflows, subworkflows, and calls in metadata MD."
  [md]
  (letfn [(branch?  [t] (and (map? t)
                             (or (:subWorkflowMetadata t)
                                 (:calls t))))
          (children [{:keys [calls subWorkflowMetadata] :as t}]
            (->> (cond subWorkflowMetadata (:calls subWorkflowMetadata)
                       calls calls
                       :else (pprint [:Missed-this-one! t]))
                 vals (apply concat)))]
    (tree-seq branch? children md)))

(defn failures-message
  "An informative [:failures :causedBy :message] from THING."
  [thing]
  (loop [thing (:failures thing)]
    (let [{:keys [causedBy message]} thing]
      (cond (vector? thing) (recur (first thing))
            (seq causedBy)  (recur causedBy)
            :else message))))

(defn truncate
  "String up to the first N characters of S."
  [s n]
  (let [front (take n s)]
    (str (str/join front)
         (if (< (count front) (count s)) "..." ""))))

;; Sometimes Cromwell md has an :end time without a :start time!
;;
(defn simplify-failed-tasks
  "Tasks in WORKFLOW-METADATA that Failed, maybe simplified."
  [workflow-metadata]
  (letfn [(interest [task]
            (select-keys task [:backendStatus :jes :jobId
                               :callRoot :start :end :failures]))
          (papi-error [msg]
            (let [re #"(?s).* PAPI error code ([^.]+)\. .*"
                  [_ code :as match?] (re-matches re msg)]
              (when match? code)))
          (maybe [m k v] (if v (assoc m k v) m))
          (fix [{:keys [end start] :as thing}]
            (let [duration (if (and end start)
                             (util/seconds-between start end)
                             {:end end :start start})
                  message  (failures-message thing)
                  failures (truncate message 777)
                  code     (papi-error message)
                  zero     (maybe {:failures-message failures
                                   :duration-seconds duration}
                                  :papi-error-code code)]
              (-> thing (dissoc :failures) (assoc :zero zero))))]
    (->> workflow-metadata flatten-workflow
         (filter (comp #{"Failed"} :executionStatus))
         (map (comp fix interest)))))

(defn fix-times
  "Interpret TIME0 and TIME1 as valid [end start] times.
  Return [] if both TIME0 and TIME1 are nil.
  If neither is nil, then the later is END and earlier START.
  If TIME1 is nil, then TIME0 is END."
  [time0 time1]
  (letfn [(parse [t]
            (when t (util/do-or-nil (inst/read-instant-date t))))
          (fail [i t]
            (if (inst? i) "" (format "Cannot read '%s' as a time." t)))
          (throw! [msg] (throw (IllegalArgumentException. msg)))]
    (let [[inst0 inst1] (map parse [time0 time1])
          failure (str/join " " (map fail [inst0 inst1] [time0 time1]))]
      (cond (and time0 time1) (if (and inst0 inst1)
                                (if (pos? (compare inst0 inst1))
                                  [inst0 inst1]
                                  [inst1 inst0])
                                (throw! failure))
            time0 (if inst0 [inst0] (throw! (fail inst0 time0)))
            :else []))))

(defn get-workflows
  "Up to MAX workflows in environment ENV with some status in STATUSES,
  and between TIME0 and TIME1."
  [env max statuses & [time0 time1]]
  (letfn [(stringify [inst] (str (.toInstant inst)))
          (maybe [m k v] (if v (assoc m k (stringify v)) m))]
    (let [[end start] (fix-times time0 time1)]
      (-> {:status (vec statuses) :includeSubworkflows false}
          (maybe :end   end)
          (maybe :start start)
          (->> (cromwell/query env)
               (take max))))))

(defn throw-or-max-count!
  "Throw or return the whole number in MAX."
  [max]
  (let [result (util/parse-int max)]
    (if (and result (nat-int? result))
      result
      (throw (IllegalArgumentException.
               (format "Error: <max> %s must be a positive integer" max))))))

(defn failed-workflows
  "The Failed workflows in ENVIRONMENT between TIME0 and TIME1."
  [environment max & [time0 time1]]
  (let [env (zero/throw-or-environment-keyword! environment)
        max-count (throw-or-max-count! max)]
    (run! pprint (get-workflows env max-count ["Failed"] time0 time1))))

(defn succeeded-workflows
  "Up to MAX Succeeded workflows in ENVIRONMENT between TIME0 and TIME1."
  [environment max & [time0 time1]]
  (let [env (zero/throw-or-environment-keyword! environment)
        max-count (throw-or-max-count! max)]
    (run! pprint (get-workflows env max-count ["Succeeded"] time0 time1))))

(defn failed-tasks
  "Up to MAX Failed tasks in ENVIRONMENT between TIME0 and TIME1."
  [environment max & [time0 time1]]
  (let [env (zero/throw-or-environment-keyword! environment)
        max-count (throw-or-max-count! max)
        metadata (partial cromwell/all-metadata env)
        wtf (comp simplify-failed-tasks metadata :id)]
    (->> (get-workflows env max-count ["Failed"] time0 time1)
         (mapcat wtf)
         (take max-count)
         (run! pprint))))

(defn readable-duration
  "Convert integer minutes to hours and minutes in the format: XXh XXm"
  [duration-minutes]
  (let [days    (quot duration-minutes 1440)
        days-r  (rem duration-minutes 1440)
        hours   (quot days-r 60)
        minutes (rem days-r 60)]
    (str/join " " (remove str/blank?
                          (vector (when (pos? days)    (str days "d"))
                                  (when (pos? hours)   (str hours "h"))
                                  (when (pos? minutes) (str minutes "m")))))))

(defn workflow-events
  [{:keys [calls] :as _workflow}]
  (letfn [(event-duration
            [{:keys [description startTime endTime] :as _event}]
            (when (and startTime endTime)
              (let [minutes (util/minutes-between startTime endTime)
                    description-kw (-> description
                                       (str/replace " " "-")
                                       (keyword))]
                (when-not (zero? minutes) {description-kw minutes}))))
          (task-events [task]
            (let [{:keys [executionEvents subWorkflowMetadata backendStatus]}
                  task]
              (if subWorkflowMetadata
                (workflow-events subWorkflowMetadata)
                (when backendStatus
                  (->> executionEvents
                       (map event-duration)
                       (into [])
                       (assoc {} (keyword backendStatus)))))))
          (call-events [[_call-name tasks]] (mapcat task-events tasks))]
    (mapcat call-events calls)))

(defn event-timing
  "Time per event type for workflow with ID in ENVIRONMENT."
  [environment id]
  (let [env (zero/throw-or-environment-keyword! environment)]
    (when-not id
      (throw (IllegalArgumentException. "Need a Cromwell workflow ID.")))
    (letfn [(combine-event-seqs [first second]
              [(->> (into first second)
                    (remove nil?)
                    (apply merge-with +))])
            (fix [m] (util/fmap readable-duration (first m)))]
      (->> {:expandSubWorkflows true}
           (cromwell/metadata env id)
           (workflow-events)
           (map (partial apply hash-map))
           (apply merge-with combine-event-seqs)
           (util/fmap fix)
           (pprint)))))

(defn workflow-tasks
  [{:keys [calls] :as _workflow}]
  (letfn [(task-duration [call-name task]
            (let [{:keys [start end subWorkflowMetadata backendStatus]} task]
              (if subWorkflowMetadata
                (workflow-tasks subWorkflowMetadata)
                (when (and start end backendStatus)
                  (let [minutes (util/minutes-between start end)
                        status-kw (keyword backendStatus)
                        call-kw (keyword call-name)]
                    (when-not (zero? minutes)
                      {status-kw {call-kw minutes}}))))))
          (call-tasks [[call-name tasks]]
            (mapcat (partial task-duration call-name) tasks))]
    (mapcat call-tasks calls)))

(defn task-timing
  "Time per task type for workflow with ID in ENVIRONMENT."
  [environment id]
  (zero/throw-or-environment-keyword! environment)
  (when-not id
    (throw (IllegalArgumentException. "Need a Cromwell workflow ID.")))
  (letfn [(mapify [[first second]] {first second})]
    (->> {:expandSubWorkflows true}
         (cromwell/metadata (keyword environment) id)
         (workflow-tasks)
         (map mapify)
         (apply merge-with (partial merge-with +))
         (util/fmap (partial util/fmap readable-duration))
         (pprint))))

(defn status-counts
  "Count workflows in each status on Cromwell in ENVIRONMENT."
  [environment]
  (let [env (zero/throw-or-environment-keyword! environment)]
    (prn (cromwell/url env))
    (pprint (cromwell/status-counts env {:includeSubworkflows false}))))

(defn metadata
  "Workflow metadata for IDS from Cromwell in ENVIRONMENT."
  [environment & ids]
  (let [env (zero/throw-or-environment-keyword! environment)]
    (letfn [(show [id] (pprint (cromwell/metadata env id)))]
      (run! show ids))))

(defn all-metadata
  "All workflow metadata for IDS from Cromwell in ENVIRONMENT."
  [environment & ids]
  (let [env (zero/throw-or-environment-keyword! environment)]
    (letfn [(show [id] (pprint (cromwell/all-metadata env id)))]
      (run! show ids))))

(defn all-metadata-json
  "JSON metadata for IDS from Cromwell in ENVIRONMENT."
  [environment & ids]
  (let [env (zero/throw-or-environment-keyword! environment)]
    (letfn [(show [id]
              (json/pprint (cromwell/all-metadata env id) :escape-slash false))]
      (run! show ids))))

(defn outputs
  "Workflow outputs for IDS from Cromwell in ENVIRONMENT."
  [environment & ids]
  (let [env (zero/throw-or-environment-keyword! environment)]
    (letfn [(show [id] (pprint (cromwell/outputs env id)))]
      (run! show ids))))

(defn status
  "Workflow status for IDS from Cromwell in ENVIRONMENT."
  [environment & ids]
  (let [env (zero/throw-or-environment-keyword! environment)]
    (letfn [(show [id] (pprint (cromwell/status env id)))]
      (run! show ids))))

(defn abort
  "Abort the Cromwell workflows with IDS in ENVIRONMENT."
  [environment & ids]
  (let [env (zero/throw-or-environment-keyword! environment)]
    (letfn [(show [id] (pprint (cromwell/abort env id)))]
      (run! show ids))))

(defn abort-all-running-cromwell-workflows
  "Abort all the running Cromwell workflows in ENVIRONMENT."
  [environment]
  (let [env (zero/throw-or-environment-keyword! environment)]
    (letfn [(show [id] (pprint (cromwell/abort env id)))]
      (->> {:status "Running" :includeSubworkflows false}
           (cromwell/query env)
           (run! (comp show :id))))))

(defn submit-all-on-hold-cromwell-workflows
  "Submit all the Cromwell workflows 'On Hold' in ENVIRONMENT."
  [environment]
  (let [env (zero/throw-or-environment-keyword! environment)]
    (letfn [(show [id] (pprint (cromwell/release-hold env id)))]
      (->> {:status ["On Hold"] :includeSubworkflows false}
           (cromwell/query env)
           (run! (comp show :id))))))

(defn json-diff
  "Differences between JSON files JSON0 and JSON1."
  [json0 json1]
  (with-open [j0 (io/reader json0)
              j1 (io/reader json1)]
    (let [[only0 only1 both] (->> [j0 j1]
                                  (map json/read)
                                  (apply data/diff))]
      (pprint {json0 only0 json1 only1 :both both}))))

(defn running-workflows
  "Return top-level workflows that are 'Running' in ENVIRONMENT."
  [environment max]
  (let [env (zero/throw-or-environment-keyword! environment)
        max-count (throw-or-max-count! max)]
    (->> {:status "Running" :includeSubworkflows false}
         (cromwell/query env)
         (take max-count)
         (run! pprint))))

(defn edn-json
  "Write the JSON equivalent of the EDN-INPUT-FILE to stdout."
  [edn-input-file]
  (letfn [(io [in] (-> in slurp edn/read-string json/pprint))]
    (if (= edn-input-file "-")
      (io *in*)
      (with-open [in (io/reader edn-input-file)] (io in)))))

(defn json-edn
  "Write the EDN equivalent of the JSON-INPUT-FILE to stdout."
  [json-input-file]
  (letfn [(io [in] (-> in slurp (json/read-str :key-fn keyword) pprint))]
    (if (= json-input-file "-")
      (io *in*)
      (with-open [in (io/reader json-input-file)] (io in)))))

(defn input-failures
  "Dump up to MAX INPUT_KEY inputs to failures count in ENVIRONMENT."
  [environment max input-key]
  (let [env       (zero/error-or-environment-keyword environment)
        max-count (throw-or-max-count! max)
        md        (partial cromwell/metadata env)]
    (pprint (->> (get-workflows env max-count ["Failed"])
                 (keep (comp (keyword input-key) :inputs md :id))
                 (take max-count)
                 frequencies
                 (sort-by second >)
                 (reduce concat)
                 (into [])))))

(defn workflows
  "Show up to MAX workflows with some status in STATUSES."
  [environment max & statuses]
  (let [env       (zero/error-or-environment-keyword environment)
        max-count (throw-or-max-count! max)]
    (->> (get-workflows env max-count statuses)
         (take max-count)
         (run! pprint))))

(defn timing
  "Show timing URLs for IDS in ENVIRONMENT."
  [environment & ids]
  (let [env (zero/error-or-environment-keyword environment)
        api (cromwell/api env)]
    (letfn [(show! [id] (prn (str/join "/" [api id "timing"])))]
      (run! show! ids))))

(defn google-userinfo
  "Nil or your OAuth2 'userinfo' from Google."
  []
  (-> {:method  :get                    ; :debug true :debug-body true
       :url     "https://www.googleapis.com/oauth2/v1/userinfo?alt=json"
       :headers (once/get-auth-header!)}
      http/request :body
      (json/read-str :key-fn keyword)
      util/do-or-nil pprint))

(def commands
  "Map diagnostic names to functions."
  (let [names ["abort"
               "abort-all-running-cromwell-workflows"
               "all-metadata"
               "all-metadata-json"
               "edn-json"
               "event-timing"
               "failed-tasks"
               "failed-workflows"
               "google-userinfo"
               "input-failures"
               "json-diff"
               "json-edn"
               "metadata"
               "outputs"
               "running-workflows"
               "status"
               "status-counts"
               "submit-all-on-hold-cromwell-workflows"
               "succeeded-workflows"
               "task-timing"
               "timing"
               "workflows"]]
    (zipmap names (map (comp resolve symbol) names))))

(defn run
  "Run diagnostic program specified by ARGS."
  [& args]
  (try
    (if-let [diagnostic (commands (first args))]
      (apply diagnostic (rest args))
      (let [error (if-let [verb (first args)]
                    (format "Error: %s is not a dx <tool>." verb)
                    "Error: Must specify a dx <tool> to run.")]
        (throw (IllegalArgumentException. error))))
    (catch Exception x
      (binding [*out* *err*] (println (describe commands)))
      (throw x))))

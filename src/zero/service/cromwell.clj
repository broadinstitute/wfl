(ns zero.service.cromwell
  "Launch a Cromwell workflow and wait for it to complete."
  (:require [clojure.data.json :as json]
            [clojure.string :as str]
            [clojure.walk :as walk]
            [clj-http.client :as http]
            [zero.debug :as debug]
            [zero.environments :as env]
            [zero.once :as once]
            [zero.util :as util]
            [zero.zero :as zero]))

(def statuses
  "The statuses a Cromwell workflow can have."
  ["Aborted"
   "Aborting"
   "Failed"
   "On Hold"
   "Running"
   "Submitted"
   "Succeeded"])

(defn url
  "URL for GotC Cromwell in ENVIRONMENT."
  [environment]
  (get-in env/stuff [environment :cromwell :url]))

(defn api
  "API URL for GotC Cromwell API in ENVIRONMENT."
  [environment]
  (str (url environment) "/api/workflows/v1"))

(defn request-json
  "Response to REQUEST with :body parsed as JSON."
  [request]
  (let [{:keys [body] :as response} (http/request request)]
    (assoc response :body (json/read-str body :key-fn keyword))))

(def bogus-key-character-map
  "Map bogus characters in metadata keys to replacements."
  (let [tag (str "%" zero/the-name "%")
        bogus {" " "SPACE"
               "(" "OPEN"
               ")" "CLOSE"}]
    (letfn [(wrap [v] (str tag v tag))]
      (zipmap (keys bogus) (map wrap (vals bogus))))))

(def bogus-key-characters
  "Set of the bogus characters found in metadata keys"
  (set (str/join (keys bogus-key-character-map))))

(defn name-bogus-characters
  "Replace bogus characters in S with their names."
  [s]
  (reduce (fn [s [k v]] (str/replace s k v))
          s bogus-key-character-map))

(defn some-thing
  "GET or POST THING to ENVIRONMENT Cromwell for workflow with ID, where
  QUERY-PARAMS is a map of extra query parameters to pass on the URL.
  HACK: Frob any BOGUS-KEY-CHARACTERS so maps can be keywordized."
  ([method thing environment id query-params]
   (letfn [(maybe [m k v] (if (seq v) (assoc m k v) m))]
     (let [edn (-> {:method  method ;; :debug true :debug-body true
                    :url     (str (api environment) "/" id "/" thing)
                    :headers (once/get-local-auth-header)}
                   (maybe :query-params query-params)
                   http/request :body json/read-str)
           bad (filter (partial some bogus-key-characters) (util/keys-in edn))
           fix (into {} (for [k bad] [k (name-bogus-characters k)]))]
       (->> edn
            (walk/postwalk-replace fix)
            walk/keywordize-keys))))
  ([method thing environment id]
   (some-thing method thing environment id {})))

(defn get-thing
  "GET the ENVIRONMENT Cromwell THING for the workflow with ID."
  ([thing environment id query-params]
   (some-thing :get thing environment id query-params))
  ([thing environment id]
   (get-thing thing environment id {})))

(defn post-thing
  "POST the ENVIRONMENT Cromwell THING for the workflow with ID."
  [thing environment id]
  (some-thing :post thing environment id))

(defn status
  "Status of the workflow with ID on Cromwell in ENVIRONMENT."
  [environment id]
  (:status (get-thing "status" environment id)))

(defn release-hold
  "Let 'On Hold' workflow with ID run on Cromwell in ENVIRONMENT."
  [environment id]
  (post-thing "releaseHold" environment id))

(defn release-a-workflow-every-10-seconds
  "Every 10 seconds release one workflow from WORKFLOW-IDS in ENVIRONMENT."
  [workflow-ids environment]
  (when (seq workflow-ids)
    (util/sleep-seconds 10)
    (release-hold environment (first workflow-ids))
    (recur (rest workflow-ids) environment)))

(defn release-workflows-using-agent
  "Return an agent running release-a-workflow-every-10-seconds on all
  the workflow IDs returned by FIND-ENVIRONMENT-AND-WORKFLOW-IDS."
  [find-environment-and-workflow-ids]
  (let [[environment & workflow-ids] (find-environment-and-workflow-ids)]
    (send-off (agent workflow-ids)
              release-a-workflow-every-10-seconds environment)))

(defn metadata
  "GET the metadata for workflow ID in ENVIRONMENT."
  ([environment id query-params]
   (get-thing "metadata" environment id query-params))
  ([environment id]
   (metadata environment id {})))

(defn all-metadata
  "Fetch all metadata from ENVIRONMENT Cromwell for workflow ID."
  [environment id]
  (metadata environment id {:expandSubWorkflows true}))

(defn outputs
  "GET the metadata for workflow ID in ENVIRONMENT."
  ([environment id query-params]
   (get-thing "outputs" environment id query-params))
  ([environment id]
   (outputs environment id {})))

(defn cromwellify-json-form
  "Translate FORM-PARAMS into the list of single-entry maps that
  Cromwell expects in its query POST request."
  [form-params]
  (letfn [(expand [[k v]] (if (vector? v)
                            (for [x v] {k (str x)})
                            [{k (str v)}]))]
    (mapcat expand form-params)))

(defn query
  "Lazy results of querying Cromwell in ENVIRONMENT with PARAMS map."
  [environment params]
  (let [form-params (merge {:pagesize 999} params)
        request {:method       :post ;; :debug true :debug-body true
                 :url          (str (api environment) "/query")
                 :form-params  (cromwellify-json-form form-params)
                 :content-type :application/json}]
    (letfn [(each [page sofar]
              (let [response (-> request
                                 (update :form-params conj {:page (str page)})
                                 (assoc :headers (once/get-local-auth-header))
                                 request-json :body)
                    {:keys [results totalResultsCount]} response
                    total (+ sofar (count results))]
                (lazy-cat results (when (< total totalResultsCount)
                                    (each (inc page) total)))))]
      (util/lazy-unchunk (each 1 0)))))

;; HACK: (into (array-map) ...) is egregious.
;;
(defn status-counts
  "Map status to workflow counts on Cromwell in ENVIRONMENT with PARAMS
  map and AUTH-HEADER."
  ([auth-header environment params]
   (letfn [(each [status]
             (let [form-params (-> {:pagesize 1 :status status}
                                   (merge params)
                                   cromwellify-json-form)]
               [status (-> {:method       :post ;; :debug true :debug-body true
                            :url          (str (api environment) "/query")
                            :form-params  form-params
                            :content-type :application/json
                            :headers      auth-header}
                           request-json :body :totalResultsCount)]))]
     (let [counts (into (array-map) (map each statuses))
           total  (apply + (map counts statuses))]
       (into counts [[:total total]]))))
  ([environment params]
   (status-counts (once/get-local-auth-header) environment params)))

(defn make-workflow-labels
  "Return the workflow labels from ENVIRONMENT, WDL, and INPUTS."
  [environment wdl inputs]
  (letfn [(unprefix [[k v]] [(keyword (last (str/split (name k) #"\."))) v])
          (key-for [suffix] (keyword (str zero/the-name "-" (name suffix))))]
    (let [the-version   (zero/get-the-version)
          wdl-value     (last (str/split wdl #"/"))
          version-value (-> the-version
                            (select-keys [:commit :version])
                            (json/write-str :escape-slash false))]
      (merge
        {(key-for :version)     version-value
         (key-for :wdl)         wdl-value
         (key-for :wdl-version) (or (the-version wdl-value) "Unknown")}
        (select-keys (into {} (map unprefix inputs))
                     (get-in env/stuff [environment :cromwell :labels]))))))

(defn post-workflow
  "Assemble PARTS into a multipart HTML body and post it to the Cromwell
  server in ENVIRONMENT, and return the workflow ID."
  [environment parts]
  (letfn [(multipartify [[k v]] {:name (name k) :content v})]
    (-> {:method    :post ;; :debug true :debug-body true
         :url       (api environment)
         :headers   (once/get-local-auth-header)
         :multipart (map multipartify parts)}
        request-json #_debug/dump :body :id)))

(defn partify-workflow
  "Return a map describing a workflow named WF to run in ENVIRONMENT
   with DEPENDENCIES, INPUTS, OPTIONS, and LABELS."
  [environment wf dependencies inputs options labels]
  (letfn [(jsonify [edn] (when edn (json/write-str edn :escape-slash false)))
          (maybe [m k v] (if v (assoc m k v) m))]
    (let [wf-labels (make-workflow-labels environment (.getName wf) inputs)
          all-labels (merge labels wf-labels)]
      (-> {:workflowSource wf
           :workflowType   "WDL"
           :labels         (jsonify all-labels)}
          (maybe :workflowDependencies dependencies)
          (maybe :workflowInputs       (jsonify inputs))
          (maybe :workflowOptions      (jsonify options))))))

(defn hold-workflow
  "Submit a workflow 'On Hold' to run WDL with IMPORTS-ZIP, INPUTS,
  OPTIONS, and LABELS on the Cromwell in ENVIRONMENT and return its
  ID.  IMPORTS-ZIP, INPUTS, OPTIONS, and LABELS can be nil.  WDL is
  the top-level wf.wdl file specifying the workflow.  IMPORTS-ZIP is a
  zip archive of WDL's dependencies.  INPUTS and OPTIONS are the
  standard JSON files for Cromwell.  LABELS is a {:key value} map."
  [environment wdl imports-zip inputs options labels]
  (post-workflow environment
                 (assoc (partify-workflow environment
                                          wdl
                                          imports-zip
                                          inputs
                                          options
                                          labels)
                        :workflowOnHold "true")))

(defn submit-workflow
  "Submit a workflow to run WDL with IMPORTS-ZIP, INPUTS,
  OPTIONS, and LABELS on the Cromwell in ENVIRONMENT and return its
  ID.  IMPORTS-ZIP, INPUTS, OPTIONS, and LABELS can be nil.  WDL is
  the top-level wf.wdl file specifying the workflow.  IMPORTS-ZIP is a
  zip archive of WDL's dependencies.  INPUTS and OPTIONS are the
  standard JSON files for Cromwell.  LABELS is a {:key value} map."
  [environment wdl imports-zip inputs options labels]
  (post-workflow environment
                 (partify-workflow environment
                                   wdl
                                   imports-zip
                                   inputs
                                   options
                                   labels)))

(defn work-around-cromwell-fail-bug
  "Wait 2 seconds and ignore up to N times a bogus failure response from
  Cromwell for workflow ID in ENVIRONMENT.  Work around the 'sore spot'
  reported in https://github.com/broadinstitute/cromwell/issues/2671"
  [n environment id]
  (util/sleep-seconds 2)
  (let [fail {"status" "fail" "message" (str "Unrecognized workflow ID: " id)}
        {:keys [body] :as bug} (try (get-thing "status" environment id)
                                    (catch Exception e (ex-data e)))]
    (debug/trace [bug n])
    (when (and (pos? n) bug
               (= 404 (:status bug))
               (= fail (json/read-str body)))
      (recur (dec n) environment id))))

(defn wait-for-workflow-complete
  "Return status of workflow named by ID when it completes."
  [environment id]
  (work-around-cromwell-fail-bug 9 environment id)
  (loop [environment environment id id]
    (let [now (status environment id)]
      (if (and now (#{"Submitted" "Running"} now))
        (do (util/sleep-seconds 15) (recur environment id))
        {:status (status environment id)}))))

(defn abort
  "Abort the workflow with ID run on Cromwell in ENVIRONMENT."
  [environment id]
  (post-thing "abort" environment id))

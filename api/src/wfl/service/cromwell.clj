(ns wfl.service.cromwell
  "Common utilities and clients to talk to Cromwell."
  (:require [clojure.data.json :as json]
            [clojure.string :as str]
            [clojure.walk :as walk]
            [clj-http.client :as http]
            [wfl.auth :as auth]
            [wfl.debug :as debug]
            [wfl.util :as util]
            [wfl.wfl :as wfl]))

(def retry-status?
  "Cromwell workflow statuses eligible for retry."
  #{"Aborted" "Failed"})

(def final?
  "The final statuses a Cromwell workflow can have."
  (conj retry-status? "Succeeded"))

(def active?
  "The statuses an active Cromwell workflow can have."
  #{"Aborting" "On Hold" "Running" "Submitted"})

(def status?
  "All the statuses a Cromwell workflow can have."
  (into active? final?))

(defn ^:private api
  "Get the api url given Cromwell URL."
  [url]
  (-> url
      util/de-slashify
      (str "/api/workflows/v1")))

(defn ^:private cromwellify-json-form
  "Translate FORM-PARAMS into the list of single-entry maps that
  Cromwell expects in its query POST request."
  [form-params]
  (letfn [(expand [[k v]] (if (vector? v)
                            (for [x v] {k (str x)})
                            [{k (str v)}]))]
    (mapcat expand form-params)))

(defn ^:private request-json
  "Response to REQUEST with :body parsed as JSON."
  [request]
  (update (http/request request) :body (fnil util/parse-json "null")))

(def ^:private bogus-key-character-map
  "Map bogus characters in metadata keys to replacements."
  (let [tag (str "%" wfl/the-name "%")
        bogus {" " "SPACE"
               "(" "OPEN"
               ")" "CLOSE"}]
    (letfn [(wrap [v] (str tag v tag))]
      (zipmap (keys bogus) (map wrap (vals bogus))))))

(def ^:private bogus-key-characters
  "Set of the bogus characters found in metadata keys"
  (set (str/join (keys bogus-key-character-map))))

(defn ^:private name-bogus-characters
  "Replace bogus characters in S with their names."
  [s]
  (reduce (fn [s [k v]] (str/replace s k v))
          s bogus-key-character-map))

(defn ^:private post-workflow
  "Assemble PARTS into a multipart HTML body and post it to the Cromwell
  server specified by URL, and return the workflow ID."
  [url parts]
  (util/response-body-json
   (http/post url {:headers   (auth/get-auth-header)
                   :multipart (util/multipart-body parts)})))

(defn make-workflow-labels
  "Return workflow labels for WDL."
  [wdl]
  (letfn [(key-for [suffix] (keyword (str wfl/the-name "-" (name suffix))))]
    {(key-for :version)     (-> (wfl/get-the-version)
                                (select-keys [:commit :version])
                                (json/write-str :escape-slash false))
     (key-for :wdl)         (last (str/split (:path wdl) #"/"))
     (key-for :wdl-version) (:release wdl)}))

(defn wdl-map->url
  "Create a http url for WDL where any imports are relative.
  Uses jsDelivr CDN to avoid GitHub rate-limiting."
  [wdl]
  (str/join "/" ["https://cdn.jsdelivr.net/gh"
                 (or (:user wdl) "broadinstitute")
                 (str (or (:repo wdl) "warp") (str "@" (:release wdl)))
                 (:path wdl)]))

(defn ^:private stringify-vals
  "Stringify all of the values of a Map."
  [m]
  (into {} (map (fn [[k v]] [k (str v)]) m)))

(defn ^:private some-thing
  "GET or POST THING to Cromwell given URL for workflow with ID, where
  QUERY-PARAMS is a map of extra query parameters to pass on the URL.
  HACK: Frob any BOGUS-KEY-CHARACTERS so maps can be keywordized."
  ([method thing url id query-params]
   (letfn [(maybe [m k v] (if (seq v) (assoc m k v) m))]
     (let [edn (-> {:method  method     ; :debug true :debug-body true
                    :url     (str (api url) "/" id "/" thing)
                    :headers (auth/get-auth-header)}
                   (maybe :query-params query-params)
                   http/request :body json/read-str)
           bad (filter (partial some bogus-key-characters) (util/keys-in edn))
           fix (into {} (for [k bad] [k (name-bogus-characters k)]))]
       (->> edn
            (walk/postwalk-replace fix)
            walk/keywordize-keys))))
  ([method thing url id]
   (some-thing method thing url id {})))

(defn ^:private get-thing
  "GET the THING for the workflow with ID from Cromwell URL."
  ([thing url id query-params]
   (some-thing :get thing url id query-params))
  ([thing url id]
   (get-thing thing url id {})))

(defn ^:private post-thing
  "POST the THING for the workflow with ID from Cromwell URL."
  [thing url id]
  (some-thing :post thing url id))

(defn ^:private partify-workflow
  "Return a map describing a workflow of running WDL
   with DEPENDENCIES, INPUTS, OPTIONS, and LABELS."
  [wdl inputs options labels]
  (letfn [(jsonify [edn] (when edn (json/write-str edn :escape-slash false)))
          (maybe [m k v] (if v (assoc m k v) m))]
    (let [wf-labels  (make-workflow-labels wdl)
          all-labels (stringify-vals (merge labels wf-labels))]
      (-> {:workflowUrl    (wdl-map->url wdl)
           :workflowType   "WDL"
           :labels         (jsonify all-labels)}
          (maybe :workflowInputs       (jsonify inputs))
          (maybe :workflowOptions      (jsonify options))))))

(defn ^:private work-around-cromwell-fail-bug
  "Wait 2 seconds and ignore up to N times a bogus failure response from
  Cromwell for workflow ID given URL.  Work around the 'sore spot'
  reported in https://github.com/broadinstitute/cromwell/issues/2671"
  [n url id]
  (util/sleep-seconds 2)
  (let [fail {"status" "fail" "message" (str "Unrecognized workflow ID: " id)}
        {:keys [body] :as bug} (try (get-thing "status" url id)
                                    (catch Exception e (ex-data e)))]
    (debug/trace [bug n])
    (when (and (pos? n) bug
               (= 404 (:status bug))
               (= fail (json/read-str body)))
      (recur (dec n) url id))))

(defn abort
  "Abort the workflow with ID run on Cromwell given URL."
  [url id]
  (post-thing "abort" url id))

(defn metadata
  "GET the metadata for workflow ID given Cromwell URL."
  ([url id query-params]
   (get-thing "metadata" url id query-params))
  ([url id]
   (metadata url id {})))

(defn all-metadata
  "Fetch all metadata for workflow ID given Cromwell URL."
  [url id]
  (metadata url id {:expandSubWorkflows true}))

(defn query
  "Lazy results of querying Cromwell given URL with PARAMS map."
  [url params]
  (let [form-params (merge {:pagesize 999} params)
        request     {:method       :post ; :debug true :debug-body true
                     :url          (str (api url) "/query")
                     :form-params  (cromwellify-json-form form-params)
                     :content-type :application/json}]
    (letfn [(each [page sofar]
              (let [response (-> request
                                 (update :form-params conj {:page (str page)})
                                 (assoc :headers (auth/get-auth-header))
                                 request-json :body)
                    {:keys [results totalResultsCount]} response
                    total    (+ sofar (count results))]
                (lazy-cat results (when (< total totalResultsCount)
                                    (each (inc page) total)))))]
      (util/lazy-unchunk (each 1 0)))))

(defn release-hold
  "Let 'On Hold' workflow with ID run on Cromwell given URL."
  [url id]
  (post-thing "releaseHold" url id))

(defn status
  "Status of the workflow with ID on Cromwell given URL."
  [url id]
  (:status (get-thing "status" url id)))

(defn submit-workflow
  "Submit a workflow to run WDL with INPUTS, OPTIONS, and LABELS
  on the Cromwell URL and return its ID.  INPUTS,
  OPTIONS, and LABELS can be nil.  WDL is a map referencing the
  workflow file on GitHub, see [[wdl-map->url]].  INPUTS and
  OPTIONS are the standard JSON files for Cromwell.  LABELS is a
  {:key value} map."
  [url wdl inputs options labels]
  (->> (partify-workflow wdl
                         inputs
                         options
                         labels)
       (post-workflow (api url))
       :id))

(defn submit-workflows
  "Batch submit one or more workflows to cromwell.
  Parameters:
   URL         - Cromwell URL
   WDL         - Workflow WDL to be executed, see [[wdl-map->url]]
   INPUTS      - Sequence of workflow inputs
   OPTIONS     - Workflow options for entire batch
   LABELS      - Labels to apply to each workflow

  Return:
   List of UUIDS for each workflow as reported by cromwell."
  [url wdl inputs options labels]
  (->> (partify-workflow wdl
                         inputs
                         options
                         labels)
       (post-workflow (str (api url) "/batch"))
       (mapv :id)))

(defn wait-for-workflow-complete
  "Return status of workflow ID from URL when it completes."
  [url id]
  (let [finished? (comp final? #(status url id))]
    (work-around-cromwell-fail-bug 9 url id)
    (util/poll finished? 15 (Integer/MAX_VALUE))))

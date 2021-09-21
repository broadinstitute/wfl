(ns wfl.util
  "Some utilities shared across this program."
  (:require [clojure.data.csv   :as csv]
            [clojure.data.json  :as json]
            [clojure.java.io    :as io]
            [clojure.java.shell :as shell]
            [clojure.spec.alpha :as s]
            [clojure.string     :as str]
            [wfl.log            :as log]
            [wfl.wfl            :as wfl])
  (:import [java.io File IOException StringWriter Writer]
           [java.nio.file Files]
           [java.nio.file.attribute FileAttribute]
           [java.time OffsetDateTime ZoneId]
           [java.time.temporal ChronoUnit]
           [java.util ArrayList Collections Random UUID]
           [java.util.concurrent TimeUnit TimeoutException]
           [javax.mail.internet InternetAddress]
           [org.apache.commons.io FilenameUtils])
  (:gen-class))

(defmacro do-or-nil
  "Value of `body` or `nil` if it throws."
  [& body]
  `(try (do ~@body)
        (catch Exception x#
          (log/warn (str/join " " [(str x#) "from wfl.util/do-or-nil"]))
          nil)))

;; Parsers that will not throw.
;;
(defn parse-int [s] (do-or-nil (Integer/parseInt s)))
(defn parse-boolean [s] (do-or-nil (Boolean/valueOf s)))

(defn parse-json
  "Parse JSON `object` into keyword->object map recursively."
  [^String object]
  (try (json/read-str object :key-fn keyword)
       (catch Throwable x
         (log/error {:exception x :object object})
         object)))

(defn response-body-json
  "Return the :body of the http `response` as JSON"
  [response]
  (-> response :body (or "null") parse-json))

(defn slurp-json
  "Nil or the JSON in FILE."
  [file]
  (do-or-nil
   (with-open [^java.io.Reader in (io/reader file)]
     (json/read in :key-fn keyword))))

(defn spit-json
  "Throw or write EDN CONTENT to FILE as JSON."
  [file content]
  (with-open [^Writer out (io/writer file)]
    (binding [*out* out]
      (json/pprint content :escape-slash false))))

(defn unprefix
  "Return the STRING with its PREFIX stripped off."
  [string prefix]
  (if (str/starts-with? string prefix)
    (subs string (count prefix))
    string))

(defn unsuffix
  "Return the STRING with its SUFFIX stripped off."
  [string suffix]
  (if (str/ends-with? string suffix)
    (subs string 0 (- (count string) (count suffix)))
    string))

(defn remove-extension
  "Remove the (last) file extension from `filename`, if one exists."
  [filename]
  (if-let [idx (str/last-index-of filename ".")]
    (subs filename 0 idx)
    filename))

(defn extension
  "Return the (last) file extension from `filename`, if one exists."
  [filename]
  (when-let [idx (str/last-index-of filename ".")]
    (subs filename (inc idx) (count filename))))

(defn basename
  "Strip directory from `filename`."
  [filename]
  (if (= "/" filename)
    filename
    (last (str/split filename #"/"))))

(defn dirname
  "Strip basename from `filename`"
  [filename]
  (if (= "/" filename)
    filename
    (if-let [idx (str/last-index-of filename "/" (- (count filename) 2))]
      (if (zero? idx) "/" (subs filename 0 idx))
      "")))

(defn deep-merge
  "Merge two or more maps recursively.
  From https://clojuredocs.org/clojure.core/merge#example-5c4874cee4b0ca44402ef622"
  [a & maps]
  (if (map? a)
    (apply merge-with deep-merge a maps)
    (apply merge-with deep-merge maps)))

(defmacro assoc-when
  "Associate `key` `value` mapping in `coll` when `pred key value`,
  otherwise `coll`"
  [coll pred key value]
  `(if (~pred ~coll ~key)
     (assoc ~coll ~key ~value)
     ~coll))

(defn delete-tree
  "Recursively delete a sequence of FILES."
  [& files]
  (when (seq files)
    (let [file (first files)
          more (.listFiles file)]
      (if (seq more)
        (recur (concat more files))
        (do (io/delete-file file :無)
            (recur (rest files)))))))

(defn copy-directory
  "Copy files from SRC to under DEST."
  [^File src ^File dest]
  (let [prefix (str (.getParent src) "/")]
    (doseq [file (file-seq src)]
      (when-not (.isDirectory file)
        (let [target (io/file dest (unprefix (.getPath file) prefix))]
          (io/make-parents target)
          (io/copy file target))))))

(defn sleep-seconds
  "Sleep for N seconds."
  [n]
  (Thread/sleep (* n 1000)))

(defn lazy-unchunk
  "Supply items from COLL one at a time to work around chunking of lazy
  sequences for HTTP pagination callbacks and so on."
  [coll]
  (lazy-seq
   (when (seq coll)
     (cons (first coll)
           (lazy-unchunk (rest coll))))))

(defn keys-in
  "Return all keys used in any maps in TREE."
  [tree]
  (letfn [(follow? [node]
            (or (map? node) (vector? node) (set? node) (list? node)))]
    (reduce into #{}
            (remove nil?
                    (map (fn [node] (when (map? node) (keys node)))
                         (tree-seq follow? identity tree))))))

(defn fmap
  "Map the function F over the values in map M."
  [f m]
  (zipmap (keys m) (map f (vals m))))

(defn minutes-between
  "The number of minutes from START to END."
  [start end]
  (.between
   ChronoUnit/SECONDS
   (OffsetDateTime/parse start)
   (OffsetDateTime/parse end)))

(defn seconds-between
  "The number of seconds from START to END."
  [start end]
  (.between
   ChronoUnit/SECONDS
   (OffsetDateTime/parse start)
   (OffsetDateTime/parse end)))

(defn summarize
  "Summarize COMMANDS in a string vector."
  [commands]
  (let [indent (str/join (repeat 4 \space))]
    (apply concat
           (for [[n f] (sort-by first commands)]
             (let [{:keys [arglists doc]} (meta f)
                   command (apply pr-str (first arglists))]
               [(str/join " " [" " n command]) (str indent doc)])))))

(defn map-keys
  "Return map `m` with `f` applied to all its keys."
  [f m]
  (into {} (map #(update-in % [0] f) m)))

(defn map-vals
  "Return map `m` with `f` applied to all its values."
  [f m]
  (into {} (map #(update-in % [1] f) m)))

(defn prefix-keys
  "Prefix all keys in map M with P."
  [m p]
  (map-keys (fn [k] (keyword (str (name p) (name k)))) m))

(defn unprefix-keys
  "Remove prefix `p` from all keys in map `m` with that prefix."
  [m p]
  (map-keys (fn [k] (keyword (unprefix (name k) (name p)))) m))

(defn absent?
  "Test if `coll` does not contain `key`.
  See also `clojure.core/contains?`
  "
  [coll key]
  (not (contains? coll key)))

(defn unnilify
  "Return a map containing only those non-nil entries in `map`."
  [map]
  {:pre [(map? map)]}
  (into {} (filter second map)))

(defn select-non-nil-keys
  "Returns a map containing only those non-nil entries in `map` whose key is
  in `keyseq`."
  [map keyseq]
  (unnilify (select-keys map keyseq)))

(defn on
  "Apply the function `g` `on` the results of mapping the unary function `f`
  over each of the input arguments in `xs`.
  Example:
  Consider two maps `a` and `b` with members `:uuid`:
     (on = :uuid a b) === (= (:uuid a) (:uuid b))
  "
  [g f & xs]
  (apply g (mapv f xs)))

(def gatk-docker-inputs
  "This is silly."
  (let [gatk {:gatk_docker "us.gcr.io/broad-gatk/gatk:4.0.10.1"}
        ab   (prefix-keys gatk :ApplyBQSR)
        br   (prefix-keys gatk :BaseRecalibrator)
        gbr  (prefix-keys gatk :GatherBqsrReports)]
    (-> (merge ab br gbr)
        (prefix-keys :UnmappedBamToAlignedBam)
        (prefix-keys :UnmappedBamToAlignedBam)
        (prefix-keys :ExomeGermlineSingleSample)
        (prefix-keys :ExomeGermlineSingleSample)
        (prefix-keys :ExomeReprocessing)
        (prefix-keys :ExomeReprocessing))))

(def gc_bias_metrics-inputs
  "This is silly too."
  (let [bias {:collect_gc_bias_metrics false}
        cam  (prefix-keys bias :CollectAggregationMetrics)
        qm   (prefix-keys bias :CollectReadgroupBamQualityMetrics)]
    (-> (merge cam qm)
        (prefix-keys :AggregatedBamQC.AggregatedBamQC)
        (prefix-keys :ExomeGermlineSingleSample)
        (prefix-keys :ExomeGermlineSingleSample)
        (prefix-keys :ExomeReprocessing)
        (prefix-keys :ExomeReprocessing))))

(defn seeded-shuffle
  "Randomize COLL consistently."
  [coll]
  (let [seed  (Random. 23)
        array (ArrayList. coll)]
    (Collections/shuffle array seed)
    (.toArray array)))

(def google-cloud-zones
  "String of GCE zones suitable for Cromwell options."
  (letfn [(reach [region zone] (str region "-" zone))
          (spray [[k v]] (map (partial reach k) v))]
    (->> {"us-central1" "abcf"
          "us-east1"    "bcd"
          "us-east4"    "abc"
          "us-west1"    "abc"
          #_#_"us-west2" "abc"}
         (mapcat spray)
         seeded-shuffle
         (str/join " "))))

(defn is-non-negative!
  "Throw unless integer value of INT-STRING is non-negative."
  [int-string]
  (let [result (parse-int int-string)]
    (when-not (nat-int? result)
      (throw (IllegalArgumentException.
              (format "%s must be a non-negative integer"
                      (if (nil? result) "" (format " (%s)" result))))))
    result))

(defn shell!
  "Run ARGS in a shell and return stdout or throw."
  [& args]
  (let [{:keys [exit err out]} (apply shell/sh args)]
    (when-not (zero? exit)
      (throw (Exception. (format "%s: %s exit status from: %s : %s"
                                 wfl/the-name exit args err))))
    (str/trim out)))

(defn shell-io!
  "Run ARGS in a subprocess with inherited standard streams."
  [& args]
  (let [exit (-> args ProcessBuilder. .inheritIO .start .waitFor)]
    (when-not (zero? exit)
      (throw (Exception. (format "%s: %s exit status from: %s"
                                 wfl/the-name exit args))))))

(def uuid-nil
  "The nil UUID."
  (UUID/fromString "00000000-0000-0000-0000-000000000000"))

(defn extract-resource
  "Extract the resource given by RESOURCE-NAME to a temporary folder
    @returns  java.io.File to the extracted resource
    @throws   java.io.IOException if the resource was not found"
  [^String resource-name]
  (let [resource (io/resource resource-name)]
    (when (nil? resource)
      (throw (IOException. (str "No such resource " resource-name))))
    (let [temp (.toFile (Files/createTempDirectory "wfl" (into-array FileAttribute nil)))
          file (io/file temp (FilenameUtils/getName resource-name))]
      (run! #(.deleteOnExit %) [temp file])
      (with-open [in (io/input-stream resource)] (io/copy in file))
      file)))

(defn between
  "Place the `middle` lexeme between [`first` `second`]."
  [[first second] middle] (str first middle second))

(defn to-comma-separated-list
  "Return the sequence `xs` composed into a comma-separated list string.
  Example:
    (to-comma-separated-list '['x 'y 'z]) => \"(x,y,z)\""
  [xs]
  (between "()" (str/join "," xs)))

(defn to-quoted-comma-separated-list
  "Return the sequence `xs` composed into a comma-separated list string.
  Example:
    (to-quoted-comma-separated-list '[x y z]) => \"('x','y','z')\""
  [xs]
  (between "()" (str/join "," (map (partial between "''") xs))))

(defn slashify
  "Ensure URL ends in a slash /."
  [url]
  (if (str/ends-with? url "/")
    url
    (str url "/")))

(defn de-slashify
  "Ensure URL does not end in a slash /."
  [url]
  (if (str/ends-with? url "/")
    (recur (subs url 0 (dec (count url))))
    url))

(defn bracket
  "`acquire`, `use` and `release` a resource in an exception-safe manner.
   Parameters
   ----------
   acquire - thunk returning newly acquired resource
   release - function to clean up resource, called before this function returns
   utilize - function that uses the resource"
  [acquire release utilize]
  (let [resource (acquire)]
    (try
      (utilize resource)
      (finally
        (release resource)))))

(defn multipart-body
  "Assemble PARTS into a multipart HTML body."
  [parts]
  (letfn [(make-part [[k v]] {:name (name k) :content v})]
    (map make-part parts)))

(defn randomize
  "Append a random suffix to `string`."
  [string]
  (str string (str/replace (UUID/randomUUID) "-" "")))

(defn curry
  "Curry the function `f` such that its arguments may be supplied across two
   applications."
  [f]
  (fn [x & xs] (apply partial f x xs)))

(defn >>>
  "Left-to-right function composition, ie `(= (>>> f g) (comp g f))`."
  [f & fs]
  (reduce #(comp %2 %1) f fs))

(defn poll
  "Poll `task!` every `seconds` [default: 1], attempting at most `max-attempts`
   [default: 3]."
  ([task! seconds max-attempts]
   (loop [attempt 1]
     (if-some [result (task!)]
       result
       (do (when (<= max-attempts attempt)
             (throw (TimeoutException. "Max number of attempts exceeded")))
           (log/debug
            (format "Sleeping - attempt #%s of %s" attempt max-attempts))
           (.sleep TimeUnit/SECONDS seconds)
           (recur (inc attempt))))))
  ([task seconds]
   (poll task seconds 3))
  ([task]
   (poll task 1)))

"A set of all mutually supported TSV file types (by FireCloud and WFL)"
(s/def ::tsv-type #{:entity :membership})

(defn terra-id
  "
  Generate a Terra-compatible primary key column name for the
  specified TSV-TYPE and base COL.

  The first column of the table must be its primary key, and named accordingly:
    entity:[entity-type]_id
    membership:[entity-type]_set_id

  For tsv-type :entity, 'entity:sample_id' will upload the .tsv data
  into a `sample` table in the workspace (or create one if it does not
  exist).

  If the table already contains a sample with that id, it will get overwritten.

  For tsv-type :membership, 'membership:sample_set_id' will append
  sample names to a `sample_set` table in the workspace (or create one
  if it does not exist).

  If the table already contains a sample set with that id, it will be
  appended to and not overwritten.

  Parameters
  ----------
    tsv-type - A member of ::tsv-type
    col      - Entity name or primary key column base

  Examples
  --------
    (terra_id :entity \"flowcell\")
    (terra_id :membership \"flowcell\")
  "
  [tsv-type col]
  {:pre [(s/valid? ::tsv-type tsv-type)]}
  (str/join [(name tsv-type)
             ":"
             (-> col (unsuffix "_id") (unsuffix "_set"))
             (when (= :membership tsv-type) "_set")
             "_id"]))

(defn columns-rows->tsv
  "
  Write COLUMNS and ROWS to a .tsv named FILE, or to bytes if FILE not specified.

  Examples
  --------
    (columns-rows->tsv [c1 c2 c3] [[x1 x2 x3] [y1 y2 y3] ...])
    (columns-rows->tsv [c1 c2 c3] [[x1 x2 x3] [y1 y2 y3] ...] \"destination.tsv\")
  "
  ([columns rows file]
   (with-open [writer (io/writer file)]
     (csv/write-csv writer columns :separator \tab)
     (csv/write-csv writer rows :separator \tab)
     file))
  ([columns rows]
   (str (columns-rows->tsv columns rows (StringWriter.)))))

(defn columns-rows->terra-tsv
  "
  Write COLUMNS and ROWS to a .tsv named FILE in a Terra-compatible format
  as dictated by TSV-TYPE, or to bytes if FILE not specified.

  Parameters
  ----------
    tsv-type - A member of ::tsv-type
    columns  - Column names (first will be formatted as primary key identifier)
    rows     - Rows with element counts matching the column count
    file     - [optional] TSV file name to dump

  Examples
  --------
    (columns-rows->terra-tsv :entity [c1 c2 c3] [[x1 x2 x3] [y1 y2 y3] ...])
    (columns-rows->terra-tsv :membership [c1 c2 c3] [[x1 x2 x3] [y1 y2 y3] ...] \"dest.tsv\")
  "
  ([tsv-type columns rows file]
   {:pre [(s/valid? ::tsv-type tsv-type)]}
   (letfn [(format-entity-type [[head & rest]]
             (cons (terra-id tsv-type head) rest))]
     (columns-rows->tsv [(format-entity-type columns)] rows file)))
  ([tsv-type columns rows]
   (str (columns-rows->terra-tsv tsv-type columns rows (StringWriter.)))))

(gen-class
 :name         wfl.util.UserException
 :extends      java.lang.Exception
 :implements   [clojure.lang.IExceptionInfo]
 :constructors {[String]                                       [String]
                [String clojure.lang.IPersistentMap]           [String]
                [String clojure.lang.IPersistentMap Throwable] [String Throwable]}
 :state        data
 :prefix       user-exception-
 :init         init)

(defn ^:private user-exception-init
  ([message]            [[message] {}])
  ([message data]       [[message] data])
  ([message data cause] [[message cause] data]))

(defn ^:private user-exception-getData [this]
  (.data this))

(defn ^:private user-exception-toString [this]
  (let [data  (.getData this)
        cause (.getCause this)]
    (cond-> (str (-> this .getClass .getName) ": " (.getMessage this))
      (and data (seq data)) (str " " data)
      cause                 (str " caused by " cause))))

(defmulti to-edn
  "Return an EDN representation of the `object` that will be shown to users."
  :type)

(defmethod to-edn :default [x] x)

(def digit?          (set "0123456789"))
(def lowercase?      (set "abcdefghijklmnopqrstuvwxyz"))
(def uppercase?      (set (map #(Character/toUpperCase %) lowercase?)))
(def letter?         (into lowercase? uppercase?))
(def alphanumeric?   (into letter? digit?))
(def spaceunderdash? (set " _-"))
(def terra-allowed?  (into alphanumeric? spaceunderdash?))
(def terra-name?     (partial every? terra-allowed?))

(defn terra-namespaced-name?
  "Return nil or the Terra [namespace name] pair in NAMESPACE-NAME."
  [namespace-name]
  (let [[namespace name & more] (str/split namespace-name #"/" 3)]
    (when (and (nil? more)
               (seq namespace) (seq name)
               (terra-name? namespace) (terra-name? name))
      [namespace name])))

(defmacro make-map
  "Map SYMBOLS as keywords to their values in the environment."
  [& symbols]
  (zipmap (map keyword symbols) symbols))

(let [alphanumunderdash? (into (set "-_") alphanumeric?)]
  (defn ^:private label-name?
    "True if `_s` starts with a letter followed by any number of letters, numbers
     underscores or dashes."
    [[first & rest :as _s]]
    (and (letter? first) (every? alphanumunderdash? rest))))

(defn ^:private label-value?
  "True if `s` is not a blank string."
  [^String s]
  (not (str/blank? s)))

(defn label?
  "True if `s` is a string of the form \"name:value\"."
  [s]
  (let [[name value & rest] (str/split s #":" 3)]
    (and (label-name? name) (label-value? value) (nil? rest))))

(defn uuid-string? [s] (uuid? (do-or-nil (UUID/fromString s))))
(defn datetime-string? [s] (do-or-nil (OffsetDateTime/parse s)))

(defn email-address?
  "True if `s` is an email address."
  [s]
  (do-or-nil (or (.validate (InternetAddress. s)) true)))

(defn utc-now
  "Return OffsetDateTime/now in UTC."
  []
  (OffsetDateTime/now (ZoneId/of "UTC")))

(defn earliest
  "Return the earliest time of `instants`."
  [& instants]
  (first (sort instants)))

(defn latest
  "Return the latest time of `instants`."
  [& instants]
  (last (sort instants)))

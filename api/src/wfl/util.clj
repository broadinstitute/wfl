(ns wfl.util
  "Some utilities shared across this program."
  (:require [clojure.data.json :as json]
            [clojure.java.io :as io]
            [clojure.java.shell :as shell]
            [clojure.string :as str]
            [clojure.tools.logging :as log]
            [vault.client.http]         ; vault.core needs this
            [vault.core :as vault]
            [wfl.wfl :as wfl])
  (:import [java.io File Writer IOException]
           [java.time OffsetDateTime]
           [java.time.temporal ChronoUnit]
           [java.nio.file Files]
           [java.nio.file.attribute FileAttribute]
           [java.util ArrayList Collections Random UUID]
           [java.util.zip ZipOutputStream ZipEntry]
           [org.apache.commons.io FilenameUtils]))

vault.client.http/http-client           ; Keep :clint eastwood quiet.

(defmacro do-or-nil
  "Value of `body` or `nil` if it throws."
  [& body]
  `(try (do ~@body)
        (catch Exception x#
          (log/warn x# "Swallowed exception and returned nil in wfl.util/do-or-nil"))))

(defmacro do-or-nil-silently
  "Value of `body` or `nil` if it throws, without logging exceptions.
  See also [[do-or-nil]]."
  [& body]
  `(try (do ~@body)
        (catch Exception x#)))

;; Parsers that will not throw.
;;
(defn parse-int [s] (do-or-nil (Integer/parseInt s)))
(defn parse-boolean [s] (do-or-nil (Boolean/valueOf s)))

(defn parse-json [^String object]
  "Parse json `object` into keyword->object map recursively"
  (json/read-str object :key-fn keyword))

(defonce the-system-environment (delay (into {} (System/getenv))))

(defn getenv
  "`(System/getenv)` as a map, or the value for `key`, or `default`."
  ([key default]
   (@the-system-environment key default))
  ([key]
   (@the-system-environment key))
  ([]
   @the-system-environment))

(defn parse-json
  "Parse the json string STR into a keyword-string map"
  [str]
  (json/read-str str :key-fn keyword))

(defn response-body-json [response]
  "Return the :body of the http `response` as JSON"
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

(defn vault-secrets
  "Return the secrets at `path` in vault."
  [path]
  (let [token (or (->> [(System/getProperty "user.home") ".vault-token"]
                       (str/join "/") slurp do-or-nil)
                  (getenv "VAULT_TOKEN" "VAULT_TOKEN"))]
    (try (vault/read-secret
          (doto (vault/new-client "https://clotho.broadinstitute.org:8200/")
            (vault/authenticate! :token token))
          path {})
         (catch Throwable e
           (log/warn e "Issue with Vault")
           (log/debug "Perhaps run 'vault login' and try again")))))

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
      (if (= idx 0) "/" (subs filename 0 idx))
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

(defn zip-files
  "Return ZIP with named FILES zipped into it."
  [^File zip & files]
  (with-open [out (ZipOutputStream. (io/output-stream zip))]
    (doseq [file files]
      (let [import (io/file file)]
        (with-open [in (io/reader import)]
          (.putNextEntry out (ZipEntry. (.getName import)))
          (io/copy in out)))))
  zip)

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
  (. ChronoUnit/MINUTES between
     (OffsetDateTime/parse start)
     (OffsetDateTime/parse end)))

(defn seconds-between
  "The number of seconds from START to END."
  [start end]
  (. ChronoUnit/SECONDS between
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

(defn prefix-keys
  "Prefix all keys in map M with P."
  [m p]
  (zipmap (map (fn [k] (keyword (str (name p) "." (name k)))) (keys m))
          (vals m)))

(defn absent?
  "Test if `coll` does not contain `key`.
  See also `clojure.core/contains?`
  "
  [coll key]
  (not (contains? coll key)))

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

(defn uuid-nil?
  "True when UUID is UUID-NIL or its string representation."
  [uuid]
  (or (= uuid uuid-nil)
      (= uuid (str uuid-nil))))

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

(defn to-quoted-comma-separated-list
  "Return the sequence `xs` composed into a comma-separated list string.
  Example:
    (to-quoted-comma-separated-list '[x y z]) => \"('x','y','z')\""
  [xs]
  (letfn [(between [[first second] x] (str first x second))]
    (between "()" (str/join "," (map (partial between "''") xs)))))

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
    (->> (seq url)
         drop-last
         (str/join ""))
    url))

(defn bracket
  "`acquire`, `use` and `release` a resource in an exception-safe manner.
   Parameters
   ----------
   acquire - thunk returning newly acquired resource
   release - function to clean up resource, called before this function returns
   use     - function that uses the resource"
  [acquire release use]
  (let [resource (acquire)]
    (try
      (use resource)
      (finally
        (release resource)))))

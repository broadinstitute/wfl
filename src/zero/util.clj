(ns zero.util
  "Some utilities shared across this program."
  (:require [clojure.data.json :as json]
            [clojure.java.io :as io]
            [clojure.java.shell :as shell]
            [clojure.string :as str]
            [buddy.sign.jwt :as jwt]
            [clj-yaml.core :as yaml]
            [vault.client.http]         ; vault.core needs this
            [vault.core :as vault]
            [zero.environments :as env]
            [zero.zero :as zero])
  (:import [com.google.auth.oauth2 GoogleCredentials]
           [java.io File Writer]
           [java.time OffsetDateTime]
           [java.time.temporal ChronoUnit]
           [java.util ArrayList Collections Random UUID]
           [java.util.zip ZipOutputStream ZipEntry]))

vault.client.http/http-client           ; Keep :clint eastwood quiet.

(defmacro do-or-nil
  "Value of BODY or nil if it throws."
  [& body]
  `(try (do ~@body)
        (catch Exception x#
          (println x#))))

;; Parsers that will not throw.
;;
(defn parse-int     [s] (do-or-nil (Integer/parseInt s)))
(defn parse-boolean [s] (do-or-nil (Boolean/valueOf  s)))

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
  "Return the vault-secrets at PATH."
  [path]
  (let [token-path (str (System/getProperty "user.home") "/.vault-token")]
    (try (vault/read-secret
           (doto (vault/new-client "https://clotho.broadinstitute.org:8200/")
             (vault/authenticate! :token (slurp token-path)))
           path)
         (catch Throwable e
           (let [error (get-in (Throwable->map e) [:via 0 :message])
                 lines ["%1$s: %2$s" "%1$s: Run 'vault login' and try again."]
                 msg   (format (str/join \newline lines) zero/the-name error)]
             (println msg))))))

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

(defn exome-inputs
  "Exome inputs for ENVIRONMENT that do not depend on the input file."
  [environment]
  (let [{:keys [vault_path_to_picard_account vault_token_path]}
        (env/stuff environment)]
    {:unmapped_bam_suffix ".unmapped.bam"
     :google_account_vault_path vault_path_to_picard_account
     :vault_token_path vault_token_path
     :papi_settings {:agg_preemptible_tries 3
                     :preemptible_tries     3}}))

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
          #_#_ "us-west2"  "abc"}
      (mapcat spray)
      seeded-shuffle
      (str/join " "))))

(defn make-options
  "Make options to run the workflow in ENVIRONMENT."
  [environment]
  (letfn [(maybe [m k v] (if-some [kv (k v)] (assoc m k kv) m))]
    (let [gcr    "us.gcr.io"
          repo   "broad-gotc-prod"
          image  "genomes-in-the-cloud:2.4.1-1540490856"
          {:keys [cromwell google]} (env/stuff environment)
          {:keys [projects jes_roots noAddress]} google]
      (-> {:backend           "PAPIv2"
           :google_project    (rand-nth projects)
           :jes_gcs_root      (rand-nth jes_roots)
           :read_from_cache   true
           :write_to_cache    true
           :default_runtime_attributes
           {:docker    (str/join "/" [gcr repo image])
            :zones     google-cloud-zones}}
        (maybe :monitoring_script cromwell)
        (maybe :noAddress noAddress)))))

(defn is-non-negative!
  "Throw unless integer value of INT-STRING is non-negative."
  [int-string]
  (let [result (parse-int int-string)]
    (when-not (nat-int? result)
      (throw (IllegalArgumentException.
               (format "%s must be a non-negative integer"
                 (if (nil? result) "" (format " (%s)" result))))))
    result))

(defonce the-system-environment (delay (into {} (System/getenv))))

(defn getenv
  "(System/getenv) as a map, or the value for KEY, or DEFAULT."
  ([key default]
   (@the-system-environment key default))
  ([key]
   (@the-system-environment key))
  ([]
   @the-system-environment))

(defn spit-yaml
  "Spit CONTENT into FILE as YAML, optionally adding COMMENTS."
  [file content & comments]
  (let [header (if (empty? comments) ""
                   (str "# " (str/join "\n# " comments) "\n\n"))
        yaml (yaml/generate-string content
               :dumper-options {:flow-style :block})]
    (spit file (str header yaml))))

(defn shell!
  "Run ARGS in a shell and return stdout or throw."
  [& args]
  (let [{:keys [exit err out]} (apply shell/sh args)]
    (when-not (zero? exit)
      (throw (Exception. (format "%s: %s exit status from: %s : %s"
                           zero/the-name exit args err))))
    (str/trim out)))

(defn shell-io!
  "Run ARGS in a subprocess with inherited standard streams."
  [& args]
  (let [exit (-> args ProcessBuilder. .inheritIO .start .waitFor)]
    (when-not (zero? exit)
      (throw (Exception. (format "%s: %s exit status from: %s"
                           zero/the-name exit args))))))

(defn create-jwt
  "Sign a JSON Web Token with OAuth secrets from environment ENV."
  [env]
  (let [{:keys [oauth2_client_id oauth2_client_secret]}
        (vault-secrets (get-in env/stuff [env :server :vault]))
        iat (quot (System/currentTimeMillis) 1000)]
    (jwt/sign {:hd    "broadinstitute.org"
               :iss   "https://accounts.google.com"
               :aud   oauth2_client_id
               :iat   iat
               :exp   (+ iat 3600000)
               :scope ["openid" "email"]} oauth2_client_secret)))

(def uuid-nil
  "The nil UUID."
  (UUID/fromString "00000000-0000-0000-0000-000000000000"))

(defn uuid-nil?
  "True when UUID is UUID-NIL or its string representation."
  [uuid]
  (or (= uuid uuid-nil)
    (= uuid (str uuid-nil))))

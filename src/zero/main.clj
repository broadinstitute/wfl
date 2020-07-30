(ns zero.main
  "Run some demonstrations."
  (:require [clojure.data.json :as json]
            [clojure.pprint :refer [pprint]]
            [clojure.string :as str]
            [clojure.tools.logging :as log]
            [zero.util :as util]
            [zero.zero :as zero])
  (:gen-class))

(defn describe
  "Describe the purpose of this program."
  [commands]
  (let [title (str (str/capitalize zero/the-name) ": ")]
    (-> [""
         "%2$s%1$s manages workflows."
         ""
         "Usage: %1$s <command> [<args> ...]"
         "Where: <command> and <args> are described below."]
        (concat (util/summarize commands)) vec (conj "")
        (->> (str/join \newline))
        (format zero/the-name title))))

(defn version
  "Show version information."
  []
  (pprint (zero/get-the-version)))

(defn version-json
  "Show version information in JSON."
  []
  (json/pprint (zero/get-the-version)))

(declare commands)

(defn help
  "Show usage help."
  []
  (println (describe commands)))

(def commands
  "Map command names to their run functions."
  (letfn [(varify [ns] (require ns) (ns-resolve ns 'run))
          (namify [ns] (last (str/split (name ns) #"\.")))]
    (let [namespaces '[zero.dx
                       zero.metadata
                       zero.module.ukb
                       zero.module.wgs
                       zero.module.xx
                       zero.server]]
      (assoc (zipmap (map namify namespaces) (map varify namespaces))
             "help"         #'help
             "version"      #'version
             "version-json" #'version-json))))

(defn trace-stack
  "Filter stack trace in #error X for only this code's frames."
  [x]
  (letfn [(mine? [f] (-> f first name (str/split #"\.") first #{"zero"}))]
    (-> x
        Throwable->map
        (update :trace (fn [t] (filterv mine? t)))
        ((juxt :cause :trace)))))

(defn exit
  "Exit this process with STATUS after SHUTDOWN-AGENTS."
  [status]
  (shutdown-agents)
  (System/exit status))

(defn -main
  "Parse THE-ARGS into a Zero command line, and run it."
  [& the-args]
  (try
    (let [[verb & args] the-args]
      (if-let [run (commands verb)]
        (do
          (apply run args)
          (exit 0))
        (do
          (log/error (if verb
                       (format "%s is not a command." verb)
                       "Must specify a <command> to run."))
          (log/debug "Output from zero.main/describe on zero.main/commands:")
          (log/debug (describe commands)))))
    (catch Exception x
      (log/error x "Uncaught exception rose to zero.main/-main")
      (log/debug "Output from zero.main/trace-stack:")
      (log/debug (trace-stack x))
      (log/debug \newline "BTW:" "You ran:" zero/the-name the-args))
    (finally (exit 1))))

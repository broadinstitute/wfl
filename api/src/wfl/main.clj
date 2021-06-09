(ns wfl.main
  "Run some demonstrations."
  (:require [clojure.data.json :as json]
            [clojure.pprint :refer [pprint]]
            [clojure.string :as str]
            [wfl.util :as util]
            [wfl.wfl :as wfl])
  (:gen-class))

(defn describe
  "Describe the purpose of this program."
  [commands]
  (let [title (str (str/capitalize wfl/the-name) ": ")]
    (-> [""
         "%2$s%1$s manages workflows."
         ""
         "Usage: %1$s <command> [<args> ...]"
         "Where: <command> and <args> are described below."]
        (concat (util/summarize commands)) vec (conj "")
        (->> (str/join \newline))
        (format wfl/the-name title))))

(defn version
  "Show version information."
  []
  (pprint (wfl/get-the-version)))

(defn version-json
  "Show version information in JSON."
  []
  (json/pprint (wfl/get-the-version)))

(declare commands)

(defn help
  "Show usage help."
  []
  (println (describe commands)))

(def commands
  "Map command names to their run functions."
  (letfn [(varify [ns] (require ns) (ns-resolve ns 'run))
          (namify [ns] (last (str/split (name ns) #"\.")))]
    (let [namespaces '[wfl.server]]
      (assoc (zipmap (map namify namespaces) (map varify namespaces))
             "help"         #'help
             "version"      #'version
             "version-json" #'version-json))))

(defn trace-stack
  "Filter stack trace in #error X for only this code's frames."
  [x]
  (letfn [(mine? [f] (-> f first name (str/split #"\.") first #{"wfl"}))]
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
  "Parse THE-ARGS into a WFL command line, and run it."
  [& the-args]
  (try
    (let [[verb & args] the-args]
      (if-let [run (commands verb)]
        (apply run args)
        (do
          (if verb (printf "%s is not a command.\n" verb))
          (help)
          (exit 1))))
    (catch Throwable t
      (println (.getMessage t))
      (exit 1)))
  (exit 0))

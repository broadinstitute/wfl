(ns wfl.tools.parallel-runner
  (:require [clojure.test :as test])
  (:import (java.io OutputStreamWriter StringWriter OutputStream)))

(defn -main
  [& args]
  (letfn [(go! [& tests]
            (let [null   (new OutputStreamWriter (OutputStream/nullOutputStream)) ;; Java 11+
                  output (new StringWriter)]
              (binding [*out* null
                        test/*test-out* output
                        test/*report-counters* (ref test/*initial-report-counters*)]
                (doseq [t tests] (t))
                [@test/*report-counters* (str output)])))]
    (let [ns      (symbol (first args))
          _       (use ns)
          tests   (group-by (comp #(contains? % :parallel) meta)
                            (filter (comp :test meta) (vals (ns-interns (the-ns ns)))))
          futures (doall (map #(future (go! %)) (tests true)))
          results (apply list (apply go! (tests false)) (map deref futures))
          summary (assoc (apply merge-with + (map first results))
                    :type :summary)]
      (run! println (filter not-empty (map second results)))
      (test/do-report summary)
      (System/exit (if (test/successful? summary) 0 1)))))

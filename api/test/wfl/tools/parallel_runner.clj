(ns wfl.tools.parallel-runner
  (:require [clojure.test :as test]
            [clojure.tools.logging :as log]
            [clojure.tools.logging.impl :refer [disabled-logger-factory]])
  (:import (java.io OutputStreamWriter StringWriter)
           (org.apache.tika.io NullOutputStream)))

(defn -main
  "Load given namespaces and run tests marked with ^:parallel in concurrent threads.

  Does not support fixtures. Will run tests without ^:parallel sequentially.
  Output will be held until all threads complete."
  [& args]
  (letfn [(go! [& tests]
            (let [null   (new OutputStreamWriter (NullOutputStream/nullOutputStream))
                  output (new StringWriter)]
              (binding [*out*                  null
                        test/*test-out*        output
                        test/*report-counters* (ref test/*initial-report-counters*)
                        log/*logger-factory*   disabled-logger-factory]
                (doseq [t tests] (t))
                [@test/*report-counters* (str output)])))]
    (let [ns      (map symbol args)
          _       (run! use ns)
          tests   (filter (comp :test meta) (apply concat (map (comp vals ns-interns the-ns) ns)))
          grouped (group-by (comp #(contains? % :parallel) meta) tests)
          futures (doall (map #(future (go! %)) (grouped true)))
          results (apply list (apply go! (grouped false)) (map deref futures))
          summary (assoc (apply merge-with + (map first results))
                    :type :summary)]
      (run! println (filter not-empty (map second results)))
      (test/do-report summary)
      (System/exit (if (test/successful? summary) 0 1)))))

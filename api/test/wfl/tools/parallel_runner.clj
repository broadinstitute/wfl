(ns wfl.tools.parallel-runner
  (:require [clojure.test :as test]
            [clojure.tools.logging :as log]
            [clojure.tools.logging.impl :refer [disabled-logger-factory]])
  (:import (java.io StringWriter)))

(defn -main
  "Concurrently run tests marked with ^:parallel in NAMESPACES.
  Run tests without ^:parallel sequentially and ignore fixtures.
  Hold output until all tests complete."
  [& namespaces]
  (let [symbols  (map symbol namespaces)
        counters (ref test/*initial-report-counters*)]
    (letfn [(run-test! [test]
              (binding [*out*                  (StringWriter.)
                        test/*test-out*        (StringWriter.)
                        test/*report-counters* counters
                        log/*logger-factory*   disabled-logger-factory]
                (test)
                (str test/*test-out*)))]
      (run! use symbols)
      (let [{parallel true, sequential false}
            (->> symbols
                 (mapcat (comp vals ns-interns the-ns))
                 (filter (comp :test meta))
                 (group-by (comp boolean :parallel meta)))
            futures (doall (map #(future (run-test! %)) parallel))
            results (concat (map run-test! sequential) (map deref futures))]
        (run! println (filter not-empty results))
        (test/do-report (assoc @counters :type :summary))
        (System/exit (if (test/successful? @counters) 0 1))))))
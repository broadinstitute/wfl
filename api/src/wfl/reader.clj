(ns wfl.reader "Clojure support for the Reader monad")

(def return constantly)

(defn bind [reader f]
  (comp f reader))

(def ask (return identity))

(defmacro let-m
  [steps & mexprs]
  (assert (vector? steps)       "a vector for its steps")
  (assert (even? (count steps)) "an even number of forms in steps vector")
  (let [x (gensym)
        g (fn [ys [y reader]] (conj ys y (list reader x)))]
    `(fn [~x] (let ~(reduce g [] (partition 2 steps))
                ~@(mapv (fn [ma] (list ma x)) mexprs)))))

(defn catch-m
  [ma handler]
  (fn [x]
    (try
      (ma x)
      (catch Exception ex
        ((handler ex) x)))))

(defn map-m [f readers]
  (fn [x] (mapv #(-> x % f) readers)))

(defn sequence-m [& readers] (map-m identity readers))

(defn run-m [f readers]
  (fn [x] (run! #(-> x % f) readers)))

(defmacro when-m
  [predicate & consequent]
  `(if ~predicate
     ~consequent
     (return nil)))

(defmacro unless-m
  [predicate & consequent]
  `(if-not ~predicate
     ~consequent
     (return nil)))

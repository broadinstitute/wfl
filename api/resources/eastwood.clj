#_:clj-kondo/ignore
(disable-warning
 {:linter :constant-test
  :if-inside-macroexpansion-of #{'wfl.util/assoc-when}})

# WFL Logging

## Usage
We use `clojure.tools.logging`.

Require it like any other dependency:
```clojure
(ns "..."
  (:require
    ...
    [clojure.tools.logging :as log]
    ...))

(log/info "Hello!")
```

Full documentation is available [here](http://clojure.github.io/tools.logging/#clojure.tools.logging),
but the primary functions we use are these:

- `log/error` and `log/errorf` for failures and uncaught exceptions
- `log/warn` and `log/warnf` for issues that could be recovered from
- `log/info` and `log/infof` for recording normal interaction
- `log/debug` and `log/debugf` for more granular output that might be 
added during debugging

The first function of each level accepts any number of arguments and 
concatenates them with spaces, while the second variant uses format 
strings. All functions can also accept a throwable before other normal 
arguments to log a specific exception.


## Testing
clojure.tools.logging provides a [test namespace](http://clojure.github.io/tools.logging/#clojure.tools.logging.test).

An example of usage is in `test/zero/unit/logging_test.clj`. 
That file doesn't actually test our code, but rather our dependencies:
it helps catch issues that might arise from clojure.tools.logging (or SLF4J, which
it currently delegates to) having trouble finding a logging implementation via
service loaders and the like. It isn't bulletproof--it is theoretically possible
for tests to use a different classpath than other execution--but it is a good smoke
test that in that any failures there would require remediation in our own codebase.

## Under the Hood
WFL's logging works as follows:

1. clojure.tools.logging is imported and used directly
   - Why clojure.tools.logging? It is Clojure-native so it has intuitive syntax
   - Why not wrap or delegate to it? It is written in macros so delegation
     is messier, the macros themselves just delegate to other logging
     implementations anyway, and its interface is so generic that we'd be
     repeating it very closely if we were to wrap it
2. clojure.tools.logging delegates to SLF4J
   - Why delegate? clojure.tools.logging is just a bunch of macros, it finds
     something to delegate to at runtime
   - Why SLF4J? Jetty already uses it, so configuring it ourselves helps keep
     it quiet, and it can delegate to Logback, which is stuck on our classpath
   - Why not Logback directly? Logback is an SLF4J implementation but
     clojure.tools.logging doesn't support it directly
3. SLF4J delegates to Logback
   - Why delegate? SLF4J is an opinionated facade that still needs something to
     delegate to
   - Why Logback? Liquibase, another dependency, makes the mistake (in SLF4J's
     eyes) of including a SLF4J *implementation*, which means we have to use it
     because SLF4J delegates via ServiceLoader only
     
Even without making our own wrapper around clojure.tools.logging, we have
a lot of flexibility. Suppose Liquibase removes their dependency on Logback:
we could simply slot in another SLF4J implementation in our dependencies and
SLF4J would pick it up, or we could remove SLF4J entirely and add something
else supported by clojure.tools.logging like 
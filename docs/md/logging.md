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

## Behavior
Currently, all error-level messages are routed to STDERR, and everything else is routed to STDOUT.

Thus, to have WFL log everything to a file you'd want to use something like
```bash
my-command >output.log 2>&1
```
That'll capture both STDOUT and STDERR to the same file.
Note that this specific syntax is more universal than just `&>output.log`.


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
     it quiet
   - Why not Log4j 2 directly? SLF4J is brought in by Jetty and will complain
     if we don't include it in our chain of delegation anywhere
3. SLF4J delegates to Log4j 2
   - Why delegate? SLF4J is an opinionated facade that still needs something to
     delegate to
   - Why Log4j 2? It is a fair default implementation: highly configurable,
     well-tested, well-supported
     
Even without making our own wrapper around clojure.tools.logging, we have
a lot of flexibility. Suppose Jetty removes their dependency on SLF4J: we
could remove our own dependency on SLF4J and clojure.tools.logging would
immediately begin interacting directly with Log4j 2.
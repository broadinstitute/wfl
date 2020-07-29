# WFL Logging

> ## Summary
> Import `clojure.tools.logging :as log` and use `log/error` / `log/info` etc.
> 
> `clojure.tools.logging` uses SLF4J under the hood which in turn uses Log4j2 as its implementation.
> 
> Below is more detailed information for those interested.

## Usage
We use `clojure.tools.logging` aliased to `log`.

Require it like any other dependency:
```clojure
(ns "..."
  (:require
    ...
    [clojure.tools.logging :as log]
    ...))

(log/info "Hello!")
```

Full documentation is available [here](http://clojure.github.io/tools.logging/#clojure.tools.logging).

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

An example of usage is in `test/zero/unit/logging_test.clj`, which exists to test that the service loaders are correctly resolving our dependencies.

## Under the Hood
> This section might be a bit verbose but hopefully it won't be too out-of-date since logging setup
> doesn't change all that much. 
>
> The key takeaway here is that JVM logging libraries use service loaders
> and other runtime configuration to find each other.

WFL's logging works as follows:

1. clojure.tools.logging is imported and used directly
   - Why clojure.tools.logging? It is Clojure-native so it has intuitive syntax
   - Why not wrap or delegate to it? It already works just as a wrapper to any
     other logging implementation so wrapping it would duplicate its purpose
2. clojure.tools.logging delegates to SLF4J
   - Why SLF4J? Jetty already uses it, so configuring it ourselves helps keep
     it quiet
3. SLF4J delegates to Log4j 2
   - Why delegate? SLF4J is a facade that still needs an implementation to
     actually log things
   - Why Log4j 2? It is a fair default implementation: highly configurable,
     well-tested, well-supported
     
Even without making our own wrapper around clojure.tools.logging, we have
a lot of flexibility. Suppose Jetty removes their dependency on SLF4J: we
could remove our own dependency on SLF4J and clojure.tools.logging would
immediately begin interacting directly with Log4j 2.
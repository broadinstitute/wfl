# WFL Logging

## TL;DR
> Import `wfl.log :as log` and use `log/error` / `log/info` etc.
> The logger will write the logs with a message, severity and timestamp by default.
>
> Example:
>
> `{"severity": "INFO", "message": "This is an information logging message.", "timestamp": "2021-07-08T22:02:58.079938Z"}`
>
> These logs are eventually sent to Google Cloud Logging and can be queried from there.
> More information about Google Logging and what some of the fields provided mean can be found here:
> https://cloud.google.com/logging/docs/agent/logging/configuration#special-fields
>
> Below is more detailed information for those interested.

## Usage
Require it like any other dependency:
```clojure
(ns "..."
  (:require
    ...
    [wfl.log :as log]
    ...))

(log/info "Hello!")
```

There are currently 5 macros for creating simple json logs for `INFO, WARN, DEBUG, ERROR, NOTICE`. There is also
a public method `log` that can be called with additional fields you may want to provide in the log, such as
labels.

A list of the Google Cloud supported logging fields and severities can be found here:
https://cloud.google.com/logging/docs/reference/v2/rest/v2/LogEntry

An example call to log:
```clojure
(ns "..."
  (:require
    ...
    [wfl.log :as log]
    ...))
(log/log :info "This is an information message with a label" :logging.googleapis.com/labels {:my-label "label value"})
```
This example produces the following json log:
```json
{"severity":"INFO","message":"This is an information message with a label","timestamp":"2021-07-09T14:57:22.437485Z","logging.googleapis.com/labels":{"my-label":"label value"}}
```
The logging can also be disabled if you see fit for whatever reason by binding the `*logger*` instance to `disabled-logger`.

Example:
```clojure
(binding [log/*logger* log/disabled-logger]
  (log/info "This message will not be written"))
```
## Testing
Test for this can be found in `test/wfl/unit/logging_test.clj`. Currently, the tests check whether the logging methods
produce json that includes the correct severity and message.

## Usage in Debugging
In order to be able to search for specific logs locally that could be useful in your debugging you will want to follow these steps:

1. Make sure you have `jq` installed for your terminal.
2. Run the server with `./ops/server.sh >> path/to/wfl/log 2>&1`
3. Look up logs by severity and only show the message: `tail -f path/to/wfl/log | grep --line-buffered -w '"severity":"[YOUR_SEVERITY_HERE]"' | jq '.message'`
4. Look up logs by a label and print the message: `tail -f path/to/wfl/log | grep --line-buffered -w 'my-label' | jq '.message'`

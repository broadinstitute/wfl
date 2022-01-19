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

## Logging Levels
The severity levels currently supported for WFL logs are, in order: DEBUG, INFO, NOTICE, ERROR, CRITICAL, ALERT, EMERGENCY.

By default, all logs of severity INFO and higher are written to stdout. If you desire to change this level, i.e. write only logs ERROR and higher or DEBUG and higher, you can set this configuration with the `logging_level` endpoint.

Calling `logging_level` with a GET request will return the current level in which the api is writing. Example on a local server below:

```
curl -X GET http://localhost:3000/api/v1/logging_level \
     -H 'accept: application/json' \
     -H "authorization: Bearer "$(gcloud auth print-access-token)
```

The result will look something like this:
```
{
  "level" : "INFO"
}
```

In order to change this level as desired would be done like so:
```
curl -X POST http://localhost:3000/api/v1/logging_level?level=DEBUG \
     -H 'accept: application/json' \
     -H "authorization: Bearer "$(gcloud auth print-access-token)
```

The result would be similar:
```
{
  "level" : "DEBUG"
}
```

The above change would allow all logs DEBUG and higher to be written, i.e. DEBUG, INFO, NOTICE,
WARNING, ERROR, CRITICAL, ALERT, EMERGENCY.

In order to change this level as desired would be done like so:
```
curl -X POST http://localhost:3000/api/v1/logging_level?level=DEBUG \
     -H 'accept: application/json' \
     -H "authorization: Bearer "$(gcloud auth print-access-token)
```

The result would be similar:
```
{
  "level" : "DEBUG"
}
```

The above change would allow all logs DEBUG and higher to be written, i.e. DEBUG, INFO, NOTICE,
WARNING, ERROR, CRITICAL, ALERT, EMERGENCY.

The result will look something like this:
```
{
  "level" : "INFO"
}
```

In order to change this level as desired would be done like so:
```
curl -X POST http://localhost:3000/api/v1/logging_level?level=DEBUG \
     -H 'accept: application/json' \
     -H "authorization: Bearer "$(gcloud auth print-access-token)
```

The result would be similar:
```
{
  "level" : "DEBUG"
}
```

The above change would allow all logs DEBUG and higher to be written, i.e. DEBUG, INFO, NOTICE,
WARNING, ERROR, CRITICAL, ALERT, EMERGENCY.

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

You may also wish to check the Logging Level section for changing the severity of messages being written. An example being that you want to have debug messages that WFL writes that are not always displayed when the app is deployed on a production server. You could set the logging level to DEBUG and then tail the messages like so:

`tail -f path/to/wfl/log | grep --line-buffered -w '"severity":"DEBUG"' | jq '.message'`

Now you can read only the debug messages in stdout as they come and filter out all other severities such as INFO.

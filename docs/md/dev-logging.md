# WFL Logging

There are macros for logging JSON records
named for each reporting level
(or severity)
supported by Google Stackdriver.

Each logging macro takes one required `expression` argument,
and an optional sequence of `key`/`value` pairs

For example:

``` clojure
(wfl.log/info (/ 22 7) :is "pi")
```

A list of the Google Cloud supported logging fields and severities can be found here:
https://cloud.google.com/logging/docs/reference/v2/rest/v2/LogEntry

Disable logging by binding `wfl.log/*logger*`
to `wfl.log/disabled-logger`.

Example:
```clojure
(binding [wfl.log/*logger* wfl.log/disabled-logger]
  (wfl.log/info "This message will not be logged."))
```

## Logging Levels

The severity levels are defined by `wfl.log/levels`.

By default,
any calls at severity `"INFO"` or above
are logged,
and severities below `"INFO"` are ignored.

The `GET /logging_level` API
returns the least severe enabled level.

```
curl -X GET http://localhost:3000/api/v1/logging_level \
     -H 'accept: application/json' \
     -H "authorization: Bearer "$(gcloud auth print-access-token)
```

The response will look something like this:
```
{
  "level" : "INFO"
}
```

Set the logging level with `POST`.
```
curl -X POST http://localhost:3000/api/v1/logging_level?level=DEBUG \
     -H 'accept: application/json' \
     -H "authorization: Bearer "$(gcloud auth print-access-token)
```

The response is similar.
```
{
  "level" : "DEBUG"
}
```

The above change allows all logs
at`"DEBUG"` and higher through.

## Log keys

Some logging frameworks,
such as Stackdriver,
treat some field keys
in log records specially.

The WFL log calls will translate keywords
qualified to the `wfl.log` namespace
into the keys expected by the framework.
For example,
when logging with Stackdriver,
the following `info` call
will add a `logging.googleapis.com/spanId`
key to the record logging `some-query`.

``` clojure
(require '[wfl.log :as log])
(let [spanId (+ 1000 (rand-int 1000)))]
  (log/info some-query ::log/spanId spanId))

```

## Testing

Tests are in `wfl.unit/logging-test`.

## Debugging

Search locally for specific logs this way.

1. Make sure you have `jq` installed for your terminal.

2. Run the server with `./ops/server.sh >> path/to/wfl/log 2>&1`

3. Look up logs by severity and only show the message.

```
tail -f path/to/wfl/log |
  grep --line-buffered -w '"severity":"[YOUR_SEVERITY_HERE]"' |
  jq .message
```

4. Look up logs by a label and print the message.

```
tail -f path/to/wfl/log |
  grep --line-buffered -w 'my-label' |
  jq .message
```

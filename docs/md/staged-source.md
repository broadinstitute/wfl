# Source

The workload `Source` models the first stage of a processing pipeline.
A `Source` reads workflow inputs from a specified location
or service in the cloud.

## User Guide
You can configure the type of `Source` used in your workload by changing the
`source` attribute of your workload request.

### `Terra DataRepo` Source
You can configure workflow-launcher to fetch workflow inputs
from a Terra Data Repository (TDR) dataset in real-time
using the `Terra DataRepo` source.

`Terra DataRepo` source polls a `dataset.table`'s row metadata table
for newly ingested rows.
It snapshots those rows for downstream processing by an `Executor`.
The `Terra DataRepo` source can only read inputs from a single table.

When you `start` the workload, the `Terra DataRepo` source will start looking
for new rows from that instant.

When you `stop` the workload, the `Terra DataRepo` source will stop looking
for new rows from that instant. All pending snapshots may continue
to be processed by a later workload stage.

!!! note
    workflow-launcher creates snapshots of your data to be processed by a
    later stage of the workload. Therefore, you must ensure the account
    `workflow-launcher@firecloud.org` is a `custodian` of your dataset.

A typical `Terra DataRepo` source configuration in the workload request looks
like:
```json
{
  "name": "Terra DataRepo",
  "dataset": "{dataset-id}",
  "table": "{dataset-table-name}",
  "snapshotReaders": [
    "{user}@{domain}",
    ...
  ],
  "pollingIntervalMinutes": 1,
  "loadTag": "{load-tag-to-poll}"
}
```
The table below summarises the purpose of each attribute in the above request.

| Attribute                | Description                                              |
|--------------------------|----------------------------------------------------------|
| `name`                   | Selects the `Terra DataRepo` source implementation.      |
| `dataset`                | The `UUID` of dataset to monitor and read from.          |
| `table`                  | The name of the `dataset` table to monitor and read from.|
| `snapshotReaders`        | A list of email addresses to set as `readers` of all snapshots created in this workload.|
| `pollingIntervalMinutes` | Optional.  Rate at which WFL will poll TDR for new rows to snapshot.|
| `loadTag`                | Optional.  Only snapshot new rows ingested to TDR with `loadTag`.|

#### `dataset`

The `UUID` uniquely identifying the TDR dataset which WFL will poll
for new workflow inputs.

#### `table`

The name of the table in `dataset` which WFL will poll
for new workflow inputs.

You should design this table such that
each row contains all the inputs required to execute a workflow by the workload
`Executor` downstream.

#### `snapshotReaders`


The email addresses of those whom should be "readers" of all snapshots created
by workflow-launcher in this workload. You can specify individual users and/or
Terra/Firecloud groups.

#### Optional `pollingIntervalMinutes`

The rate, in minutes, at which WFL will poll TDR for new rows to snapshot.
If not provided, the default interval is 20 minutes.

#### Optional `loadTag`

WFL will only snapshot new rows ingested to TDR with `loadTag`.
If not provided, all new rows will be eligible for snapshotting.

!!! note
    When initiating a TDR ingest, one can optionally set a load tag.
    Each row ingested will have this load tag set in its corresponding
    TDR row metadata table (only visible in BigQuery and not TDR UI).
    The same load tag can be reused across multiple ingests to logically link them.

    For questions on how to set the load tag for a particular flavor of ingest,
    contact #dsp-jade

### `TDR Snapshots` Source

You can configure workflow-launcher to use a list of TDR snapshots directly.
This may be useful if you don't want workflow-launcher to be a custodian of your
dataset or if you already have snapshots you want to process. In this case you
must ensure that `workflow-launcher@firecloud.org` is a `reader` of all
snapshots you want it to process.

A typical `TDR Snapshots` source configuration in the workload request looks
like:
```json
{
  "name": "TDR Snapshots",
  "snapshots": [
    "{snapshot-id}",
    ...
  ]
}
```

The table below summarises the purpose of each attribute in the above request.

| Attribute   | Description                                        |
|-------------|----------------------------------------------------|
| `name`      | Selects the `TDR Snapshots` source implementation. |
| `snapshots` | A List of `UUID`s of snapshots to process.         |

!!! note
    You must ensure that the snapshots you list are compatible with the
    downstream processing stage that consumes them.

## Developer Guide
A source is a `Queue` that satisfies the `Source` protocol below:
```clojure
(defprotocol Source
  (start-source!
    ^Unit
    [^Connection transaction  ;; JDBC Connection
     ^Source     source       ;; This source instance
    ]
    "Start enqueuing items onto the `source`'s queue to be consumed by a later
     processing stage. This operation should not perform any long-running
     external effects other than database operations via the `transaction`. This
     function is called at most once during a workload's operation.")
  (stop-source!
    ^Unit
    [^Connection transaction  ;; JDBC Connection
     ^Source     source       ;; This source instance
    ]
    "Stop enqueuing inputs onto the `source`'s queue to be consumed by a later
     processing stage. This operation should not perform any long-running
     external effects other than database operations via the `transaction`. This
     function is called at most once during a workload's operation and will only
     be called after `start-source!`. Any outstanding items on the `source`
     queue may still be consumed by a later processing stage.")
  (update-source!
    ^Workload
    [^Workload workload]
    "Enqueue items onto the `workload`'s source queue to be consumed by a later processing
     stage unless stopped, performing any external effects as necessary.
     Implementations should avoid maintaining in-memory state and making long-
     running external calls, favouring internal queues to manage such tasks
     asynchronously between invocations. This function is called one or more
     times after `start-source!` and may be called after `stop-source!`"))
```

!!! note
    The `Source` protocol is implemented by a set of multimethods of the same
    name. The use of a protocol is to illustrate the difference between the
    in-memory data model of a `Source` and the metadata seen by a user.

To be used in a workload,
a `Source` implementation
should satisfy the processing `Stage` protocol
and the `to-edn` multimethod
in addition to the following multimethods
specific to sinks:

```clojure
(defmulti create-source
  "Create a `Source` instance using the database `transaction` and configuration
   in the source `request` and return a `[type items]` pair to be written to a
   workload record as `source_type` and `source_items`.
   Notes:
   - This is a factory method registered for workload creation.
   - The `Source` type string must match a value of the `source` enum in the
     database schema.
   - This multimethod is type-dispatched on the `:name` association in the
     `request`."
  (fn ^[^String ^String]
      [^Connection         transaction  ;; JDBC Connection
       ^long               workload-id  ;; ID of the workload being created
       ^IPersistentHashMap request      ;; Data forwarded to the handler
      ]
      (:name request)))

(defmulti load-source!
  "Return the `Source` implementation associated with the `source_type` and
   `source_items` fields of the `workload` row in the database. Note that this
   multimethod is type-dispatched on the `:source_type` association in the
   `workload`."
  (fn ^Sink
      [^Connection         transaction  ;; JDBC Connection
       ^IPersistentHashMap workload     ;; Row from workload table
      ]
      (:source_type workload)))
```

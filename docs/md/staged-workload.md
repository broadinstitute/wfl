# Staged Workloads

A staged workload is a discrete body of work, which takes data from a source,
pushes it into a workflow executor for analysis,
and then delivers the results of the analysis to an output location (also known as a sink).

## Staged Workload Components
### Source
The workload [Source](./source.md) models the first stage of a processing pipeline.
In a typical workload configuration, a `Source` can be used to read workflow inputs
from a specified location or service in the cloud.

### Executor
The workload [Executor](./executor.md) models an intermediate stage of a processing pipeline.
In a typical workload configuration, an `Executor` uses a supported
service in the cloud to execute workflows.

### Sink
The workload [Sink](./sink.md) models the terminal stage of a processing pipeline.
In a typical workload configuration, a `Sink` can be used to write workflow outputs
to a desired location in the cloud.

## Example Staged Workload
The specific values below are from the
[COVID-19 Surveillance in Terra](./modules-covid.md) project.
Workloads for other projects may leverage different implementations for source, executor or sink.

!!! tip "`watchers` of a workload"
    You may have noticed the optional `watchers` field in the requests.
    As of `2021/08` WFL supports registering Slack channels
    (Slack channel IDs that starts with a `C`)
    as watchers of the workload.
    Your channel needs to live in the `broadinstitute.slack.com` Slack organization
    and you also have to `/invite @WorkFlow Launcher Notifier`
    to invite the WFL notifier to your channel
    before the channel can receive user facing messaged from WFL.

```
{
    "watchers": [
        ["slack", "C000XXX0XXX"],
        ["email", "tester@broadinstitute.org"]
    ],
    "labels": [
        "hornet:test"
    ],
    "project": "wfl-dev/CDC_Viral_Sequencing",
    "source": {
        "name": "Terra DataRepo",
        "dataset": "4bb51d98-b4aa-4c72-b76a-1a96a2ee3057",
        "table": "flowcells",
        "column": "last_modified_date",
        "snapshotReaders": [
            "workflow-launcher-dev@firecloud.org"
        ]
    },
    "executor": {
        "name": "Terra",
        "workspace": "wfl-dev/CDC_Viral_Sequencing",
        "methodConfiguration": "wfl-dev/sarscov2_illumina_full",
        "methodConfigurationVersion": 1,
        "fromSource": "importSnapshot"
    },
    "sink": {
        "name": "Terra Workspace",
        "workspace": "wfl-dev/CDC_Viral_Sequencing",
        "entityType": "flowcell",
        "identifier": "run_id",
        "fromOutputs": {
            "submission_xml" : "submission_xml",
            "assembled_ids" : "assembled_ids",
            "num_failed_assembly" : "num_failed_assembly",
            ...
        }
    }
}
```

## Staged Workload Anatomy (High Level)

| Field    | Type   | Description                                                                                                                                                                                                                                 |
|----------|--------|---------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------|
| watchers | List   | An optional list of emails or Slack channels to notify                                                                                                                                                                                                |
| labels   | List   | A list of user-defined labels.They must be a string of the form `"name":"value”`, where `name` must start with a letter followed by any combination of digits, letters, spaces, underscores and hyphens and `value` is any non-blank string |
| project  | String | The project is a non-null string required in the workload table. It's needed to support querying workloads by project                                                                                                                       |
| source   | Object | The data source                                                                                                                                                                                                                             |
| executor | Object | The mechanism executing the analysis. (Most often this is Terra)                                                                                                                                                                            |
| sink     | Object | The location where data will be placed after analysis is complete                                                                                                                                                                           |

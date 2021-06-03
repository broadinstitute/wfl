# Workloads

## What is it?
Within the context of the workflow launcher, a workload is a discrete body of work, which takes data from a source, pushes it into a workflow executor for analysis, and then delivers the results of the analysis to an output location (also known as a sink).

## Workload Components
### Source
### Executor
### Sink

## Example workload 
(The specific values below are from the COVID-19 Surveillance in Terra project. Workloads for other projects may have different fields available in the source, executor and sink).

```
{
    "watchers": [],
    "labels": [
        "hornet:test"
    ],
    "project": "wfl-dev/CDC_Viral_Sequencing",
    "pipeline": null,
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
        "workspace": "wfl-dev/CDC_Viral_Sequencing",
        "methodConfiguration": "wfl-dev/sarscov2_illumina_full",
        "methodConfigurationVersion": 1,
        "fromSource": "importSnapshot",
        "name": "Terra"
    },
    "sink": {
        "name": "Terra Workspace",
        "workspace": "wfl-dev/CDC_Viral_Sequencing",
        "entityType": "flowcell",
        "identifier": "run_id",
        "skipValidation": "true",
        "fromOutputs": { }
    }
}
```

# COVID namespace

WorkFlow Launcher (WFL) runs within the `covid` namespace to process workflows using the `Sarscov2IlluminaFull` pipeline. 

Workflow Launcher uses a model which includes a source, a sink and an executor. Within the `covid` namespace, the source at the time of this writing is either directly from the Terra Data Repository (`TerraDataRepo`), or a list of snapshots which are passed to WFL (`TDR Snapshots`). 

### API

The `covid` module supports the following API endpoints:

| Verb | Endpoint                            | Description                                                              |
|------|-------------------------------------|--------------------------------------------------------------------------|
| GET  | `/api/v1/workload`                  | List all workloads, optionally filtering by uuid or project              |
| POST | `/api/v1/create`                    | Create a new workload                                                    |
| POST | `/api/v1/start`                     | Start a workload                                                         |
| POST | `/api/v1/stop`                      | Stop a running workload                                                  |
| POST | `/api/v1/exec`                      | Create and start (execute) a workload                                    |

The life-cycle of a workload is a multi-stage process:

1. The caller needs to create a workload and specify the source, sink and executor.

    - The [Source](./source.md) must be either a snapshot or a list of snapshots. If the former, then the type is `TDR Snapshots`. If the latter, it is of type `TDRSnapshotListSource`.
     
    - The [Executor](./executor.md) must be of type `TerraExecutor`
     
    - The [Sink](./sink.md) must be of type `TerraWorkspaceSink`. 
      
    If everything passes verification, then the caller will receive a response which will contain the `uuid` of the workload.
   
2. Next, the caller needs to "start" the newly created workload, which will begin the analysis. Once started, Workflow Launcher will continue to poll for new inputs to the source until it is stopped (see #3 below).
   
3. WFL can, in addition, stop watching a workflow using the `stop` endpoint. This will not cancel analysis, but WFL will stop managing the workload, which means that it will no longer poll for new inputs to that workload.

To give more information, here are some example inputs to the above endpoints:

**GET /api/v1/workload**

==="Sample Request"

    ```
    curl 'http://localhost:8080/api/v1/workload' \
        -H 'Authorization: Bearer '$(gcloud auth print-access-token) \
        -H 'Accept: application/json'
    ```

**GET /api/v1/workload/{uuid}/workflows**

=== "Sample Request"

    ```
    curl -X GET 'http://localhost:8080/api/v1/workload/ed6e8637-5bae-4602-8b7f-f5a1bbfd2406/workflows' 
         -H 'Authorization: Bearer '$(gcloud auth print-access-token)      
         -H 'Accept: application/json'
    ```

**POST /api/v1/create**

=== "Sample Request"

    ```
    curl -X "POST" "http://localhost:8080/api/v1/create" \
         -H 'Authorization: Bearer '$(gcloud auth print-access-token) \
         -H 'Accept: application/json' \
         -H 'Content-Type: application/json' \
         -d '{
                "watchers": [
                    "tester@123.com"
                ],
                "labels": [
                    "hornet:test"
                ],
                "project": "wfl-dev/CDC_Viral_Sequencing _ranthony_bashing_copy",
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
                    "workspace": "wfl-dev/CDC_Viral_Sequencing _ranthony_bashing_copy",
                    "methodConfiguration": "wfl-dev/sarscov2_illumina_full",
                    "methodConfigurationVersion": 2,
                    "fromSource": "importSnapshot"
                },
                "sink": {
                    "name": "Terra Workspace",
                    "workspace": "wfl-dev/CDC_Viral_Sequencing _ranthony_bashing_copy",
                    "entityType": "flowcell",
                    "identifier": "run_id",
                    "fromOutputs": {
                        "submission_xml" : "submission_xml",
                        "assembled_ids" : "assembled_ids",
                        "num_failed_assembly" : "num_failed_assembly",
                        ...
                    }
                }
            }'
    ```

**POST /api/v1/start**

=== "Sample Request"

    ```
    curl -X POST 'http://localhost:8080/api/v1/start' \
         -H 'Authorization: Bearer '$(gcloud auth print-access-token) \
         -H 'Accept: application/json' \
         -H 'Content-Type: application/json' \
         -d $'{ "uuid": "fb06bcf3-bc10-471b-a309-b2f99e4f5a67" }'
    ```

**POST /api/v1/stop**

=== "Sample Request"

    ```
    curl -X POST 'http://localhost:8080/api/v1/start' \
         -H 'Authorization: Bearer '$(gcloud auth print-access-token) \
         -H 'Accept: application/json' \
         -H 'Content-Type: application/json' \
         -d $'{ "uuid": "fb06bcf3-bc10-471b-a309-b2f99e4f5a67" }'
    ```

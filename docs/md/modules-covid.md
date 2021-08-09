# COVID module

WorkFlow Launcher (WFL) uses the `covid` module to automate the sequencing
of COVID-positive samples in Terra workspaces for better understanding
of SARS-CoV-2 spread and evolution.

For this processing, WFL follows a [staged workload](./staged-workload.md) model
which includes a source, executor, and sink.

### API

The `covid` module supports the following API endpoints:

| Verb | Endpoint                            | Description                                                              |
|------|-------------------------------------|--------------------------------------------------------------------------|
| GET  | `/api/v1/workload`                  | List all workloads, optionally filtering by uuid or project              |
| GET  | `/api/v1/workload/{uuid}/workflows` | List all workflows for workload `uuid`, optionally filtering by status   |
| POST | `/api/v1/workload/{uuid}/retry`     | Retry workflows matching a given status in workload `uuid`               |
| POST | `/api/v1/create`                    | Create a new workload                                                    |
| POST | `/api/v1/start`                     | Start a workload                                                         |
| POST | `/api/v1/stop`                      | Stop a running workload                                                  |
| POST | `/api/v1/exec`                      | Create and start (execute) a workload                                    |

The life-cycle of a workload is a multi-stage process:

1. The caller needs to [create](#create-workload) a workload
   and specify the source, executor, and sink.

    - For continuous processing, the [Source](./source.md) request
      is expected to have name [`Terra DataRepo`](./source.md#terra-datarepo-source)
      and specify a Terra Data Repository (TDR) dataset to poll and snapshot.
      In one-off processing or development, we may instead use name
      [`TDR Snapshots Source`](./source.md#tdr-snapshots-source)
      to specify a list of existing TDR snapshots.
     
    - The [Executor](./executor.md) request is expected to have name
      [`Terra`](./executor.md#terra-executor) and specify the Terra workspace
      configuration for executing workflows.

    - The [Sink](./sink.md) request is expected to have name
      [`Terra Workspace`](./sink.md#terra-workspace-sink)
      and specify the Terra workspace configuration for saving workflow outputs.
      
    If all stage requests pass verification,
    then the caller will receive a response containing the workload's `uuid`.
   
2. Next, the caller needs to [start](#start-workload) the newly created workload, which will begin the analysis.
   Once started, WFL will continue to poll for new inputs to the source until it is stopped.

3. WFL can, in addition, [stop](#stop-workload) watching a workflow.
   This will not cancel analysis, but WFL will stop polling for new inputs to that workload,
   and will mark the workload finished once any previously-identified inputs have undergone processing.

    - Example: the caller may wish to stop a continuous workflow
      if maintenance is required on the underlying method.

The caller can also [retry](#retry-workload) workflows in a workload
matching a given status (ex. "Failed").

To give more information, here are some example inputs to the above endpoints:

### Get Workloads

!!! warning "Note"
    A request to the `/api/v1/workload` endpoint without a `uuid`
    or `project` parameter returns all workloads known to WFL.
    That response might be large and take awhile to process.

**GET /api/v1/workload?uuid={uuid}**

Query WFL for a workload by its UUID.

Note that a successful response from `/api/v1/workload` will
always be an array of workload objects, but specifying a `uuid`
will return a singleton.

=== "Request"

    ```
    curl -X GET 'http://localhost:3000/api/v1/workload' \
        -H 'Authorization: Bearer '$(gcloud auth print-access-token) \
        -H 'Accept: application/json' \
        -d $'{ "uuid": "e66c86b2-120d-4f7f-9c3a-b9eaadeb1919" }'
    ```

=== "Response"

    ```
    [ {
    "started" : "2021-07-14T15:36:47Z",
    "watchers" : [ "okotsopo@broadinstitute.org" ],
    "labels" : [ "hornet:test", "project:okotsopo testing enhanced source, executor, sink logging" ],
    "creator" : "okotsopo@broadinstitute.org",
    "updated" : "2021-08-06T21:41:28Z",
    "created" : "2021-07-14T15:36:07Z",
    "source" : {
    "snapshots" : [ "67a2bfd7-88e4-4adf-9e41-9b0d04fb32ea" ],
    "name" : "TDR Snapshots"
    },
    "finished" : "2021-08-06T21:41:28Z",
    "commit" : "9719eda7424bf5b0804f5493875681fa014cdb29",
    "uuid" : "e66c86b2-120d-4f7f-9c3a-b9eaadeb1919",
    "executor" : {
    "workspace" : "wfl-dev/CDC_Viral_Sequencing_okotsopo_20210707",
    "methodConfiguration" : "wfl-dev/sarscov2_illumina_full",
    "methodConfigurationVersion" : 41,
    "fromSource" : "importSnapshot",
    "name" : "Terra"
    },
    "version" : "0.8.0",
    "sink" : {
    "workspace" : "wfl-dev/CDC_Viral_Sequencing_okotsopo_20210707",
    "entityType" : "flowcell",
    "fromOutputs" : {
    "submission_xml" : "submission_xml",
    "assembled_ids" : "assembled_ids",
    ...
    },
    "identifier" : "run_id",
    "name" : "Terra Workspace"
    }
    } ]
    ```

**GET /api/v1/workload?project={project}**

Query WFL for all workloads with a specified `project` label.

The response has the same format as when specifying a UUID,
except the array may contain multiple workload objects
that share the same `project` value.

=== "Request"

    ```
    curl -X GET 'http://localhost:3000/api/v1/workload' \
        -H 'Authorization: Bearer '$(gcloud auth print-access-token) \
        -H 'Accept: application/json' \
        -d $'{ "project": "PO-1234" }'
    ```

### Get Workflows

**GET /api/v1/workload/{uuid}/workflows**

Query WFL for all workflows associated with workload `uuid`.

The response is a list of Firecloud-derived
[workflows](https://firecloud-orchestration.dsde-prod.broadinstitute.org/#/Submissions/workflowMetadata)
and their [outputs](https://firecloud-orchestration.dsde-prod.broadinstitute.org/#/Submissions/workflowOutputsInSubmission)
when available.

=== "Request"

    ```
    curl -X GET 'http://localhost:3000/api/v1/workload/e66c86b2-120d-4f7f-9c3a-b9eaadeb1919/workflows' \
         -H 'Authorization: Bearer '$(gcloud auth print-access-token) \
         -H 'Accept: application/json'
    ```

=== "Response"

    ```
    [ {
    "inputs" : {
    "biosample_to_genbank.docker" : "quay.io/broadinstitute/viral-phylo:2.1.19.1",
    "instrument_model" : "Illumina NovaSeq 6000",
    ...
    },
    "uuid" : "53f70344-6f0f-47fb-adee-4e780fb3f19a",
    "status" : "Failed",
    "outputs" : { },
    "updated" : "2021-08-06T21:41:28Z"
    } ]
    ```

**GET /api/v1/workload/{uuid}/workflows?status={status}**

Query WFL for all workflows with `status` associated with workload `uuid`.

The response has the same format as when querying without the status restriction.

=== "Request"

    ```
    curl -X GET 'http://localhost:3000/api/v1/workload/e66c86b2-120d-4f7f-9c3a-b9eaadeb1919/workflows' \
        -H 'Authorization: Bearer '$(gcloud auth print-access-token) \
        -H 'Accept: application/json' \
        -d $'{ "status": "Failed" }'
    ```

### Retry Workload

**POST /api/v1/workload/{uuid}/retry?status={status}**

Resubmit all workflows matching `status` associated with workload `uuid`.

Prerequisites:

- The status is [supported](./usage-retry.md#supported-statuses)
- Workflows must exist in the workload for the specified status
- The workload should be started

With all prerequisite fulfilled, WFL will then...

- Submit the retry to the executor
- (If necessary) remark the workload as active
  so that it will be visible in the update loop
- Return the updated workload

Further information found in general
[retry documentation](./usage-retry.md#retrying-terra-workflows-via-wfl-api).

=== "Request"

    ```
    curl -X POST "http://localhost:3000/api/v1/workload/e66c86b2-120d-4f7f-9c3a-b9eaadeb1919/retry" \
        -H 'Authorization: Bearer '$(gcloud auth print-access-token) \
        -H "Content-Type: application/json" \
        -d '{ "status": "Aborted" }'
    ```

=== "Response"

    ```
    {
    "started" : "2021-07-14T15:36:47Z",
    "watchers" : [ "okotsopo@broadinstitute.org" ],
    "labels" : [ "hornet:test", "project:okotsopo testing enhanced source, executor, sink logging", "project:wfl-dev/CDC_Viral_Sequencing_okotsopo_20210707" ],
    "creator" : "okotsopo@broadinstitute.org",
    "updated" : "2021-08-09T21:45:50Z",
    "created" : "2021-07-14T15:36:07Z",
    "source" : {
    "snapshots" : [ "67a2bfd7-88e4-4adf-9e41-9b0d04fb32ea" ],
    "name" : "TDR Snapshots"
    },
    "commit" : "9719eda7424bf5b0804f5493875681fa014cdb29",
    "uuid" : "e66c86b2-120d-4f7f-9c3a-b9eaadeb1919",
    "executor" : {
    "workspace" : "wfl-dev/CDC_Viral_Sequencing_okotsopo_20210707",
    "methodConfiguration" : "wfl-dev/sarscov2_illumina_full",
    "methodConfigurationVersion" : 43,
    "fromSource" : "importSnapshot",
    "name" : "Terra"
    },
    "version" : "0.8.0",
    "sink" : {
    "workspace" : "wfl-dev/CDC_Viral_Sequencing_okotsopo_20210707",
    "entityType" : "flowcell",
    "fromOutputs" : {
    "submission_xml" : "submission_xml",
    "assembled_ids" : "assembled_ids",
    ...
    },
    "identifier" : "run_id",
    "name" : "Terra Workspace"
    }
    }
    ```

### Create Workload

**POST /api/v1/create**

Create a new workload from a request.
Expected request format documented within [staged workload](./staged-workload.md)
navigation.

The response will be the newly created workload object with an assigned `uuid`.

=== "Request"

    ```
    curl -X "POST" "http://localhost:3000/api/v1/create" \
         -H 'Authorization: Bearer '$(gcloud auth print-access-token) \
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

=== "Response"

    ```
    TODO!
    ```

### Start Workload

**POST /api/v1/start?uuid={uuid}**

Start an existing, unstarted workload `uuid`.

The response will be the updated workload object.
See [create endpoint documentation](#create-workload) for response example.

=== "Request"

    ```
    curl -X POST 'http://localhost:3000/api/v1/start' \
         -H 'Authorization: Bearer '$(gcloud auth print-access-token) \
         -H 'Content-Type: application/json' \
         -d $'{ "uuid": "fb06bcf3-bc10-471b-a309-b2f99e4f5a67" }'
    ```

### Stop Workload

**POST /api/v1/stop?uuid={uuid}**

Stop a running workload `uuid`.

The response will be the updated workload object.
See [create endpoint documentation](#create-workload) for response example.

=== "Request"

    ```
    curl -X POST 'http://localhost:3000/api/v1/stop' \
         -H 'Authorization: Bearer '$(gcloud auth print-access-token) \
         -H 'Content-Type: application/json' \
         -d $'{ "uuid": "fb06bcf3-bc10-471b-a309-b2f99e4f5a67" }'
    ```

### Execute Workload

**POST /api/v1/exec**

Create and start (execute) a workload from a request.
Expected request format documented within [staged workload](./staged-workload.md)
navigation.

The response will be the newly created and running workload object with an assigned `uuid`.

See [create endpoint documentation](#create-workload) for request and response examples,
but with modified endpoint.
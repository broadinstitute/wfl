# GPArrays workload

The "GPArrays" workload launches workflows in Terra. Because Terra submits workflows to Cromwell
using the RAWLs API, both the input data and pipeline must be imported into a Terra workspace
prior to analysis.

In addition, the workload differs from the other WFL modules in several ways:
- The `executor` field is the Terra API URL (instead of the Cromwell URL)
- The `project` is the Terra workspace to use for processing, and must follow the format 
"{workspaceNamespace}/{workspaceName}".

## Inputs
A `GPArrays` workload specifies the following inputs for each workflow:
- `entity_name` 
- `entity_type`

#### `entity_name` (required)
- The unique id of the sample in the Terra data table. The id field follows
the format "{table_name}_id".

#### `entity_type` (required)

- The name of the Terra workspace data table containing the sample 
(e.g the `entity_type` of a row in the "sample" table is "sample")

## Usage

A `GPArrays` workload supports the following API endpoints:

| Verb | Endpoint                     | Description                                                    |
|------|------------------------------|----------------------------------------------------------------|
| GET  | `/api/v1/workload`           | List all workloads                                             |
| GET  | `/api/v1/workload/{uuid}`    | Query for a workload by its UUID                               |
| GET  | `/api/v1/workload/{project}` | Query for a workload by its Project name                       |
| POST | `/api/v1/create`             | Create a new workload                                          |
| POST | `/api/v1/start`              | Start a workload                                               |
| POST | `/api/v1/exec`               | Create and start (execute) a workload                          |

### Create Workload: `/api/v1/create`
Creates a WFL workload. Before processing, confirm that the Terra proxy SA for the
wfl-non-prod SA has at least read access to the input files, and 
the vault token path used by the Arrays pipeline.

=== "Request"

    ```bash
    curl --location --request POST 'https://dev-wfl.gotc-dev.broadinstitute.org/api/v1/create' \
    --header 'Authorization: Bearer '$(gcloud auth print-access-token) \
    --header 'Content-Type: application/json' \
    --data-raw '{
      "executor": "https://firecloud-orchestration.dsde-dev.broadinstitute.org",
      "output": "gs://broad-gotc-dev-wfl-ptc-test-outputs/arrays-test-output/",
      "pipeline": "GPArrays",
      "project": "general-dev-billing-account/arrays",
      "items": [
        {
          "inputs": {
            "entity_name": "200598830050_R07C01-1",
            "entity_type": "sample"
          }
        }
      ]
    }'
    ```

=== "Response"

    ```json
    {
      "creator": "user@domain",
      "pipeline": "GPArrays",
      "executor": "https://firecloud-orchestration.dsde-dev.broadinstitute.org",
      "release": "Arrays_v2.3.0",
      "created": "YYYY-MM-DDTHH:MM:SSZ",
      "output": "gs://broad-gotc-dev-wfl-ptc-test-outputs/arrays-test-output/",
      "project": "general-dev-billing-account/arrays",
      "commit": "commit-ish",
      "wdl": "pipelines/broad/arrays/single_sample/Arrays.wdl",
      "uuid": "74d96a04-fea7-4270-a02b-a319dae2dd5e",
      "version": "X.Y.Z"
    }
    ```

Note that the GPArrays module does NOT support specifying
Cromwell "workflowOptions" via the `options` map, since this functionality
is not exposed via the Terra API.

### Start Workload: `/api/v1/start`

Creates a new "submission" in Terra, which starts a Cromwell workflow for each item in the workload. 
Note that the workflow `uuid` in the response is the Terra submission uuid, not the Cromwell workflow uuid.

=== "Request"

    ```bash
    curl --location --request POST 'https://dev-wfl.gotc-dev.broadinstitute.org/api/v1/start' \
    --header 'Authorization: Bearer '$(gcloud auth print-access-token) \
    --header 'Content-Type: application/json' \
    --data-raw '{"uuid": "74d96a04-fea7-4270-a02b-a319dae2dd5e"}'
    ```

=== "Response"

    ```json
    {
      "started": "YYYY-MM-DDTHH:MM:SSZ",
      "creator": "user@domain",
      "pipeline": "GPArrays",
      "executor": "https://firecloud-orchestration.dsde-dev.broadinstitute.org",
      "release": "Arrays_v2.3.0",
      "created": "YYYY-MM-DDTHH:MM:SSZ",
      "output": "gs://broad-gotc-dev-wfl-ptc-test-outputs/arrays-test-output/",
      "workflows": [
        {
          "updated": "YYYY-MM-DDTHH:MM:SSZ",
          "uuid": "2c543b29-2db9-4643-b81b-b16a0654c5cc",
          "inputs": {
            "entity_name": "200598830050_R07C01-1",
            "entity_type": "sample"
          }
        }
      ],
      "project": "general-dev-billing-account/arrays",
      "commit": "commit-ish",
      "wdl": "pipelines/broad/arrays/single_sample/Arrays.wdl",
      "uuid": "74d96a04-fea7-4270-a02b-a319dae2dd5e",
      "version": "X.Y.Z"
    }
    ```

### Exec Workload: `/api/v1/exec`

Creates and then starts a WFL workload.

=== "Request"

    ```bash
    curl --location --request POST 'https://dev-wfl.gotc-dev.broadinstitute.org/api/v1/exec' \
    --header 'Authorization: Bearer '$(gcloud auth print-access-token) \
    --header 'Content-Type: application/json' \
    --data-raw '{
      "executor": "https://firecloud-orchestration.dsde-dev.broadinstitute.org",
      "output": "gs://broad-gotc-dev-wfl-ptc-test-outputs/arrays-test-output/",
      "pipeline": "GPArrays",
      "project": "general-dev-billing-account/arrays",
      "items": [
        {
          "inputs": {
            "entity_name": "200598830050_R07C01-1",
            "entity_type": "sample"
          }
        }
      ]
    }'
    ```

=== "Response"

    ```json
    {
      "started": "YYYY-MM-DDTHH:MM:SSZ",
      "creator": "user@domain",
      "pipeline": "GPArrays",
      "executor": "https://firecloud-orchestration.dsde-dev.broadinstitute.org",
      "release": "Arrays_v2.3.0",
      "created": "YYYY-MM-DDTHH:MM:SSZ",
      "output": "gs://broad-gotc-dev-wfl-ptc-test-outputs/arrays-test-output/",
      "workflows": [
        {
          "updated": "YYYY-MM-DDTHH:MM:SSZ",
          "uuid": "2c543b29-2db9-4643-b81b-b16a0654c5cc",
          "inputs": {
            "entity_name": "200598830050_R07C01-1",
            "entity_type": "sample"
          }
        }
      ],
      "project": "general-dev-billing-account/arrays",
      "commit": "commit-ish",
      "wdl": "pipelines/broad/arrays/single_sample/Arrays.wdl",
      "uuid": "74d96a04-fea7-4270-a02b-a319dae2dd5e",
      "version": "X.Y.Z"
    }
    ```

### Query Workload: `/api/v1/workload?uuid=<uuid>`

Queries the WFL database for workloads. Specify the uuid to query for a specific workload.

=== "Request"

    ```bash
    curl --location --request GET 'https://dev-wfl.gotc-dev.broadinstitute.org/api/v1/workload?uuid=2c543b29-2db9-4643-b81b-b16a0654c5cc' \
    --header 'Authorization: Bearer '$(gcloud auth print-access-token)
    ```

=== "Response"

    ```json
    [{
      "creator": "user@domain",
      "pipeline": "GPArrays",
      "executor": "https://firecloud-orchestration.dsde-dev.broadinstitute.org",
      "release": "Arrays_v2.3.0",
      "created": "YYYY-MM-DDTHH:MM:SSZ",
      "output": "gs://broad-gotc-dev-wfl-ptc-test-outputs/arrays-test-output/",
      "workflows": [
        {
          "updated": "YYYY-MM-DDTHH:MM:SSZ",
          "uuid": "2c543b29-2db9-4643-b81b-b16a0654c5cc",
          "inputs": {
            "entity_name": "200598830050_R07C01-1",
            "entity_type": "sample"
           }
        }
      ],
      "project": "general-dev-billing-account/arrays",
      "commit": "commit-ish",
      "wdl": "pipelines/broad/arrays/single_sample/Arrays.wdl",
      "uuid": "74d96a04-fea7-4270-a02b-a319dae2dd5e",
      "version": "X.Y.Z"
    }]
    ```

The "workflows" field lists out each Terra submission, and includes the status information
of the workflow that was started for that sample. It is also possible to use the Terra UI 
to check workflow progress and easily see information about any workflow failures.

### Query Workload with project: `/api/v1/workload?project=<project>`

Queries the WFL database for workloads. Specify the project name to query for a list of specific workload(s).

=== "Request"

    ```bash
    curl --location --request GET 'https://dev-wfl.gotc-dev.broadinstitute.org/api/v1/workload?project=general-dev-billing-account/arrays' \
    --header 'Authorization: Bearer '$(gcloud auth print-access-token)
    ```

=== "Response"

    ```json
    [{
      "creator": "user@domain",
      "pipeline": "GPArrays",
      "executor": "https://firecloud-orchestration.dsde-dev.broadinstitute.org",
      "release": "Arrays_v2.3.0",
      "created": "YYYY-MM-DDTHH:MM:SSZ",
      "output": "gs://broad-gotc-dev-wfl-ptc-test-outputs/arrays-test-output/",
      "workflows": [
        {
          "updated": "YYYY-MM-DDTHH:MM:SSZ",
          "uuid": "2c543b29-2db9-4643-b81b-b16a0654c5cc",
          "inputs": {
            "entity_name": "200598830050_R07C01-1",
            "entity_type": "sample"
           }
        }
      ],
      "project": "general-dev-billing-account/arrays",
      "commit": "commit-ish",
      "wdl": "pipelines/broad/arrays/single_sample/Arrays.wdl",
      "uuid": "74d96a04-fea7-4270-a02b-a319dae2dd5e",
      "version": "X.Y.Z"
    }]
    ```

The "workflows" field lists out each Terra submission, and includes the status information
of the workflow that was started for that sample. It is also possible to use the Terra UI 
to check workflow progress and easily see information about any workflow failures.

!!! warning "Note"
    `project` and `uuid` are optional path parameters to the `/api/v1/workload` endpoint,
    hitting this endpoint without them will return all workloads. However, they cannot be specified
    together.

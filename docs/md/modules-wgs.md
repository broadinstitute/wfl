# ExternalWholeGenomeReprocessing workload

## Inputs
An `ExternalWholeGenomeReprocessing` workload
specifies the following inputs
for each workflow:

- `input_cram` or `input_bam` (required)
- `base_file_name`
- `sample_name`
- `final_gvcf_base_name`
- `unmapped_bam_suffix`
- `reference_fasta_prefix`

#### `input_cram` or `input_bam` (required)

- Absolute GCS file path (like `gs://...`)

#### `base_file_name`

- Used for naming intermediate/output files
- Defaults to the filename of the `input_cram` or `input_bam`
without the `.cram` or `.bam` extension

#### `sample_name`

- Defaults to the filename of the `input_cram` or `input_bam`
without the `.cram` or `.bam` extension

#### `final_gvcf_base_name`

- Path to the final VCF (`.vcf` will be added by the WDL)
- Defaults to the filename of the `input_cram` or `input_bam`
without the `.cram` or `.bam` extension

#### `unmapped_bam_suffix`

- Defaults to `.unmapped.bam`

#### `reference_fasta_prefix`

- [Defaults to](https://github.com/broadinstitute/wfl/blob/main/api/src/wfl/references.clj#L34) `gs://gcp-public-data--broad-references/hg38/v0/Homo_sapiens_assembly38`

Note that this pipeline supports specifying arbitrary WDL inputs, either
at the workload level through `common` or individually via `items`.

## Usage

ExternalWholeGenomeReprocessing workload supports the following API endpoints:

| Verb | Endpoint                            | Description                                                    |
|------|-------------------------------------|----------------------------------------------------------------|
| GET  | `/api/v1/workload`                  | List all workloads, optionally filtering by uuid or project    |
| GET  | `/api/v1/workload/{uuid}/workflows` | List all workflows for a specified workload uuid               |
| POST | `/api/v1/create`                    | Create a new workload                                          |
| POST | `/api/v1/start`                     | Start a workload                                               |
| POST | `/api/v1/stop`                      | Stop a running workload                                        |
| POST | `/api/v1/exec`                      | Create and start (execute) a workload                          |

???+ warning "Permissions in production"
    External Whole Genome Reprocessing in `gotc-prod` uses a set of execution projects, please refer to
    [this page](https://github.com/broadinstitute/gotc-deploy/blob/master/deploy/gotc-prod/helm/WFL_README.md)
    when you have questions about permissions.

### Create Workload: `POST /api/v1/create`
Creates a WFL workload. Before processing, confirm that the WFL and Cromwell service accounts have
at least read access to the input files.

=== "Request"

```bash
curl -X POST 'https://dev-wfl.gotc-dev.broadinstitute.org/api/v1/create' \
     -H 'Authorization: Bearer '$(gcloud auth print-access-token) \
     -H 'Content-Type: application/json' \
     -d '{
           "executor": "https://cromwell-gotc-auth.gotc-dev.broadinstitute.org",
           "output": "gs://broad-gotc-dev-wfl-ptc-test-outputs/wgs-test-output/",
           "pipeline": "ExternalWholeGenomeReprocessing",
           "project": "PO-1234",
           "items": [{
             "inputs": {
               "input_cram": "gs://broad-gotc-dev-wfl-ptc-test-inputs/single_sample/plumbing/truth/develop/20k/NA12878_PLUMBING.cram",
               "sample_name": "TestSample1234"
             }
           }]
         }'
```

=== "Response"

```json
    {
      "creator": "sehsan@broadinstitute.org",
      "pipeline": "ExternalWholeGenomeReprocessing",
      "executor": "https://cromwell-gotc-auth.gotc-dev.broadinstitute.org",
      "release": "ExternalWholeGenomeReprocessing_v1.0",
      "created": "2020-10-05T15:50:01Z",
      "output": "gs://broad-gotc-dev-wfl-ptc-test-outputs/wgs-test-output/",
      "project": "PO-1234",
      "commit": "d65371ca983b4f0d4fa06868e2946a8e3cab291b",
      "wdl": "pipelines/reprocessing/external/wgs/ExternalWholeGenomeReprocessing.wdl",
      "input": "gs://broad-gotc-dev-wfl-ptc-test-inputs/single_sample/plumbing/truth",
      "uuid": "74d96a04-fea7-4270-a02b-a319dae2dd5e",
      "version": "0.7.0"
    }
```

Note that the ExternalWholeGenomeReprocessing pipeline supports specifying
cromwell "workflowOptions" via the `options` map. See the
[reference page](./usage-workflow-options) for more information.

### Start Workload: `POST /api/v1/start`

Starts a Cromwell workflow for each item in the workload. If an output already exists in the output bucket for a
particular input cram, WFL will not re-submit that workflow.

=== "Request"

```bash
curl -X POST 'https://dev-wfl.gotc-dev.broadinstitute.org/api/v1/start' \
      -H 'Authorization: Bearer '$(gcloud auth print-access-token) \
      -H 'Content-Type: application/json' \
      -d '{ "uuid": "74d96a04-fea7-4270-a02b-a319dae2dd5e" }'
```

=== "Response"

```json
    {
      "started": "2020-10-05T15:50:51Z",
      "creator": "username@broadinstitute.org",
      "pipeline": "ExternalWholeGenomeReprocessing",
      "executor": "https://cromwell-gotc-auth.gotc-dev.broadinstitute.org",
      "release": "ExternalWholeGenomeReprocessing_v1.0",
      "created": "2020-10-05T15:50:01Z",
      "output": "gs://broad-gotc-dev-wfl-ptc-test-outputs/wgs-test-output/",
      "project": "PO-1234",
      "commit": "d65371ca983b4f0d4fa06868e2946a8e3cab291b",
      "wdl": "pipelines/reprocessing/external/wgs/ExternalWholeGenomeReprocessing.wdl",
      "input": "gs://broad-gotc-dev-wfl-ptc-test-inputs/single_sample/plumbing/truth",
      "uuid": "74d96a04-fea7-4270-a02b-a319dae2dd5e",
      "version": "0.7.0"
    }
```

### Stop Workload: `POST /api/v1/stop`

Included for compatibility with continuous workloads.

=== "Request"

```bash
curl -X POST 'https://dev-wfl.gotc-dev.broadinstitute.org/api/v1/start' \
     -H 'Authorization: Bearer '$(gcloud auth print-access-token) \
     -H 'Content-Type: application/json' \
     -d '{ "uuid": "74d96a04-fea7-4270-a02b-a319dae2dd5e" }'
```

=== "Response"

 ```json
    {
      "started": "2020-10-05T15:50:51Z",
      "creator": "username@broadinstitute.org",
      "pipeline": "ExternalWholeGenomeReprocessing",
      "executor": "https://cromwell-gotc-auth.gotc-dev.broadinstitute.org",
      "release": "ExternalWholeGenomeReprocessing_v1.0",
      "created": "2020-10-05T15:50:01Z",
      "output": "gs://broad-gotc-dev-wfl-ptc-test-outputs/wgs-test-output/",
      "project": "PO-1234",
      "commit": "d65371ca983b4f0d4fa06868e2946a8e3cab291b",
      "wdl": "pipelines/reprocessing/external/wgs/ExternalWholeGenomeReprocessing.wdl",
      "input": "gs://broad-gotc-dev-wfl-ptc-test-inputs/single_sample/plumbing/truth",
      "uuid": "74d96a04-fea7-4270-a02b-a319dae2dd5e",
      "version": "0.7.0"
    }
```

### Exec Workload: `POST /api/v1/exec`

Creates and then starts a Cromwell workflow for each item in the workload.

=== "Request"

```bash
curl -X POST 'https://dev-wfl.gotc-dev.broadinstitute.org/api/v1/exec' \
     -H 'Authorization: Bearer '$(gcloud auth print-access-token) \
     -H 'Content-Type: application/json' \
     -d '{
           "executor": "https://cromwell-gotc-auth.gotc-dev.broadinstitute.org",
           "output": "gs://broad-gotc-dev-wfl-ptc-test-outputs/wgs-test-output/",
           "pipeline": "ExternalWholeGenomeReprocessing",
           "project": "PO-1234",
           "items": [{
             "inputs": {
               "input_cram": "gs://broad-gotc-dev-wfl-ptc-test-inputs/single_sample/plumbing/truth/develop/20k/NA12878_PLUMBING.cram",
               "sample_name": "TestSample1234"
             }
           }]
         }'
```

=== "Response"

```json
    {
      "started": "2020-10-05T16:15:32Z",
      "creator": "username@broadinstitute.org",
      "pipeline": "ExternalWholeGenomeReprocessing",
      "executor": "https://cromwell-gotc-auth.gotc-dev.broadinstitute.org",
      "release": "ExternalWholeGenomeReprocessing_v1.0",
      "created": "2020-10-05T16:15:32Z",
      "output": "gs://broad-gotc-dev-wfl-ptc-test-outputs/wgs-test-output/",
      "project": "PO-1234",
      "commit": "d65371ca983b4f0d4fa06868e2946a8e3cab291b",
      "wdl": "pipelines/reprocessing/external/wgs/ExternalWholeGenomeReprocessing.wdl",
      "input": "gs://broad-gotc-dev-wfl-ptc-test-inputs/single_sample/plumbing/truth",
      "uuid": "3a13f732-9743-47a9-ab83-c467b3bf0ca4",
      "version": "0.7.0"
    }
```

### Query Workload: `GET /api/v1/workload?uuid=<uuid>`

Queries the WFL database for workloads. Specify the uuid to query for a specific workload.

=== "Request"

```bash
curl -X GET 'https://dev-wfl.gotc-dev.broadinstitute.org/api/v1/workload?uuid=813e3c38-9c11-4410-9888-435569d91d1d' \
     -H 'Authorization: Bearer '$(gcloud auth print-access-token)
```

=== "Response"

```json
    [{
      "creator": "username",
      "pipeline": "ExternalWholeGenomeReprocessing",
      "executor": "https://cromwell-gotc-auth.gotc-dev.broadinstitute.org/",
      "release": "ExternalWholeGenomeReprocessing_v1.0",
      "created": "2020-08-27T16:26:59Z",
      "output": "gs://broad-gotc-dev-zero-test/wgs-test-output",
      "workflows": [
        {
          "updated": "2020-10-05T16:15:32Z",
          "uuid": "2c543b29-2db9-4643-b81b-b16a0654c5cc",
          "inputs": {
            "input_cram": "gs://broad-gotc-dev-wfl-ptc-test-inputs/single_sample/plumbing/truth/develop/20k/NA12878_PLUMBING.cram",
            "sample_name": "TestSample1234"
          }
        }
      ],
      "project": "wgs-dev",
      "commit": "d2fc38c61c62c44f4fd4d24bdee3121138e6c09e",
      "wdl": "pipelines/reprocessing/external/wgs/ExternalWholeGenomeReprocessing.wdl",
      "input": "gs://broad-gotc-test-storage/single_sample/plumbing/truth",
      "uuid": "813e3c38-9c11-4410-9888-435569d91d1d",
      "version": "0.7.0"
    }]
```

### Query Workload with project: `GET /api/v1/workload?project=<project>`

Queries the WFL database for workloads. Specify the project name to query for a list of specific workload(s).

=== "Request"

```bash
curl -X GET 'https://dev-wfl.gotc-dev.broadinstitute.org/api/v1/workload?project=wgs-dev' \
     -H 'Authorization: Bearer '$(gcloud auth print-access-token)
```

=== "Response"

```json
    [{
      "creator": "username",
      "pipeline": "ExternalWholeGenomeReprocessing",
      "executor": "https://cromwell-gotc-auth.gotc-dev.broadinstitute.org/",
      "release": "ExternalWholeGenomeReprocessing_v1.0",
      "created": "2020-08-27T16:26:59Z",
      "output": "gs://broad-gotc-dev-zero-test/wgs-test-output",
      "project": "wgs-dev",
      "commit": "d2fc38c61c62c44f4fd4d24bdee3121138e6c09e",
      "wdl": "pipelines/reprocessing/external/wgs/ExternalWholeGenomeReprocessing.wdl",
      "input": "gs://broad-gotc-test-storage/single_sample/plumbing/truth",
      "uuid": "813e3c38-9c11-4410-9888-435569d91d1d",
      "version": "0.7.0"
    }]
```

The "workflows" field lists out each Cromwell workflow that was started, and
includes their status information. It is also possible to use the Job Manager
to check workflow progress and easily see information about any workflow
failures.

!!! warning "Note"
    `project` and `uuid` are optional path parameters to the `/api/v1/workload` endpoint,
    hitting this endpoint without them will return all workloads. However, they cannot be specified
    together.

### List workflows managed by a workload `GET /api/v1/workload/{uuid}/workflows`

=== "Request"

```bash
curl -X GET 'https://dev-wfl.gotc-dev.broadinstitute.org/api/v1/workload/813e3c38-9c11-4410-9888-435569d91d1d/workflows' \
     -H 'Authorization: Bearer '$(gcloud auth print-access-token)
```

=== "Response"

```json
[{
    "updated": "2020-10-05T16:15:32Z",
    "uuid": "2c543b29-2db9-4643-b81b-b16a0654c5cc",
    "inputs": {
      "input_cram": "gs://broad-gotc-dev-wfl-ptc-test-inputs/single_sample/plumbing/truth/develop/20k/NA12878_PLUMBING.cram",
      "sample_name": "TestSample1234"
    }
}]
```

The "workflows" endpoint lists out each Cromwell workflow that was started, and includes their
status information. It is also possible to use the Job Manager to check workflow progress and
easily see information about any workflow failures.

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

| Verb | Endpoint                     | Description                                                    |
|------|------------------------------|----------------------------------------------------------------|
| GET  | `/api/v1/workload`           | List all workloads                                             |
| GET  | `/api/v1/workload/{uuid}`    | Query for a workload by its UUID                               |
| GET  | `/api/v1/workload/{project}` | Query for a workload by its Project name                       |
| POST | `/api/v1/create`             | Create a new workload                                          |
| POST | `/api/v1/start`              | Start a workload                                               |
| POST | `/api/v1/exec`               | Create and start (execute) a workload                          |

### Create Workload: `/api/v1/create`
Creates a WFL workload. Before processing, confirm that the WFL and Cromwell service accounts have
at least read access to the input files.

=== "Request"

    ```bash
    curl --location --request POST 'https://dev-wfl.gotc-dev.broadinstitute.org/api/v1/create' \
    --header 'Authorization: Bearer '$(gcloud auth print-access-token) \
    --header 'Content-Type: application/json' \
    --data-raw '{
      "cromwell": "https://cromwell-gotc-auth.gotc-dev.broadinstitute.org",
      "output": "gs://broad-gotc-dev-wfl-ptc-test-outputs/wgs-test-output/",
      "pipeline": "ExternalWholeGenomeReprocessing",
      "project": "PO-1234",
      "items": [
        {
          "inputs": {
            "input_cram": "gs://broad-gotc-dev-wfl-ptc-test-inputs/single_sample/plumbing/truth/develop/20k/NA12878_PLUMBING.cram",
            "sample_name": "TestSample1234"
          }
        }
      ]
    }'
    ```

=== "Response"

    ```json
    {
      "creator": "sehsan@broadinstitute.org",
      "pipeline": "ExternalWholeGenomeReprocessing",
      "cromwell": "https://cromwell-gotc-auth.gotc-dev.broadinstitute.org",
      "release": "ExternalWholeGenomeReprocessing_v1.0",
      "created": "2020-10-05T15:50:01Z",
      "output": "gs://broad-gotc-dev-wfl-ptc-test-outputs/wgs-test-output/",
      "project": "PO-1234",
      "commit": "d65371ca983b4f0d4fa06868e2946a8e3cab291b",
      "wdl": "pipelines/reprocessing/external/wgs/ExternalWholeGenomeReprocessing.wdl",
      "input": "gs://broad-gotc-dev-wfl-ptc-test-inputs/single_sample/plumbing/truth",
      "uuid": "74d96a04-fea7-4270-a02b-a319dae2dd5e",
      "version": "0.3.2"
    }
    ```

Note that the ExternalWholeGenomeReprocessing pipeline supports specifying
cromwell "workflowOptions" via the `options` map. See the
[reference page](./workflow-options.md) for more information.

### Start Workload: `/api/v1/start`

Starts a Cromwell workflow for each item in the workload. If an output already exists in the output bucket for a
particular input cram, WFL will not re-submit that workflow.

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
      "started": "2020-10-05T15:50:51Z",
      "creator": "username@broadinstitute.org",
      "pipeline": "ExternalWholeGenomeReprocessing",
      "cromwell": "https://cromwell-gotc-auth.gotc-dev.broadinstitute.org",
      "release": "ExternalWholeGenomeReprocessing_v1.0",
      "created": "2020-10-05T15:50:01Z",
      "output": "gs://broad-gotc-dev-wfl-ptc-test-outputs/wgs-test-output/",
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
      "project": "PO-1234",
      "commit": "d65371ca983b4f0d4fa06868e2946a8e3cab291b",
      "wdl": "pipelines/reprocessing/external/wgs/ExternalWholeGenomeReprocessing.wdl",
      "input": "gs://broad-gotc-dev-wfl-ptc-test-inputs/single_sample/plumbing/truth",
      "uuid": "74d96a04-fea7-4270-a02b-a319dae2dd5e",
      "version": "0.3.2"
    }
    ```

### Exec Workload: `/api/v1/exec`

Creates and then starts a Cromwell workflow for each item in the workload.

=== "Request"

    ```bash
    curl --location --request POST 'https://dev-wfl.gotc-dev.broadinstitute.org/api/v1/exec' \
    --header 'Authorization: Bearer '$(gcloud auth print-access-token) \
    --header 'Content-Type: application/json' \
    --data-raw '{
      "cromwell": "https://cromwell-gotc-auth.gotc-dev.broadinstitute.org",
      "output": "gs://broad-gotc-dev-wfl-ptc-test-outputs/wgs-test-output/",
      "pipeline": "ExternalWholeGenomeReprocessing",
      "project": "PO-1234",
      "items": [
        {
          "inputs": {
            "input_cram": "gs://broad-gotc-dev-wfl-ptc-test-inputs/single_sample/plumbing/truth/develop/20k/NA12878_PLUMBING.cram",
            "sample_name": "TestSample1234"
          }
        }
      ]
    }'
    ```

=== "Response"

    ```json
    {
      "started": "2020-10-05T16:15:32Z",
      "creator": "username@broadinstitute.org",
      "pipeline": "ExternalWholeGenomeReprocessing",
      "cromwell": "https://cromwell-gotc-auth.gotc-dev.broadinstitute.org",
      "release": "ExternalWholeGenomeReprocessing_v1.0",
      "created": "2020-10-05T16:15:32Z",
      "output": "gs://broad-gotc-dev-wfl-ptc-test-outputs/wgs-test-output/",
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
      "project": "PO-1234",
      "commit": "d65371ca983b4f0d4fa06868e2946a8e3cab291b",
      "wdl": "pipelines/reprocessing/external/wgs/ExternalWholeGenomeReprocessing.wdl",
      "input": "gs://broad-gotc-dev-wfl-ptc-test-inputs/single_sample/plumbing/truth",
      "uuid": "3a13f732-9743-47a9-ab83-c467b3bf0ca4",
      "version": "0.3.2"
    }
    ```

### Query Workload: `/api/v1/workload?uuid=<uuid>`

Queries the WFL database for workloads. Specify the uuid to query for a specific workload.

=== "Request"

    ```bash
    curl --location --request GET 'https://dev-wfl.gotc-dev.broadinstitute.org/api/v1/workload?uuid=813e3c38-9c11-4410-9888-435569d91d1d' \
    --header 'Authorization: Bearer '$(gcloud auth print-access-token)
    ```

=== "Response"

    ```json
    [{
      "creator": "username",
      "pipeline": "ExternalWholeGenomeReprocessing",
      "cromwell": "https://cromwell-gotc-auth.gotc-dev.broadinstitute.org/",
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
      "version": "0.1.7"
    }]
    ```

The "workflows" field lists out each Cromwell workflow that was started, and includes their
status information. It is also possible to use the Job Manager to check workflow progress and
easily see information about any workflow failures.

### Query Workload with project: `/api/v1/workload?project=<project>`

Queries the WFL database for workloads. Specify the project name to query for a list of specific workload(s).

=== "Request"

    ```bash
    curl --location --request GET 'https://dev-wfl.gotc-dev.broadinstitute.org/api/v1/workload?project=wgs-dev' \
    --header 'Authorization: Bearer '$(gcloud auth print-access-token)
    ```

=== "Response"

    ```json
    [{
      "creator": "username",
      "pipeline": "ExternalWholeGenomeReprocessing",
      "cromwell": "https://cromwell-gotc-auth.gotc-dev.broadinstitute.org/",
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
      "version": "0.1.7"
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

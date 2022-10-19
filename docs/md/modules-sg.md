# GDCWholeGenomeSomaticSingleSample

## Inputs

In addition to the standard workload request inputs:

- `executor` : URL of the Cromwell service
- `output`   : GCS URL prefix for output files
- `pipeline` : literally `"GDCWholeGenomeSomaticSingleSample"`
- `project`  : some tracking label you can choose

a `GDCWholeGenomeSomaticSingleSample` workload
requires the following inputs
for each workflow.

- `base_file_name`
- `contamination_vcf_index`
- `contamination_vcf`
- `cram_ref_fasta_index`
- `cram_ref_fasta`
- `dbsnp_vcf_index`
- `dbsnp_vcf`
- `input_cram`

Here is what those are.

#### `base_file_name`

The leaf name
of a sample input or output path
without the `.` suffix.
The `base_file_name`
is usually the same
as the _sample name_
and differs in every workflow.

#### `contamination_vcf_index` and `contamination_vcf`

These are GCS pathnames
of the contamination detection data
for the input samples.
This commonly depends
on the reference genome
for the samples,
and is shared
across all the workflows.

#### `cram_ref_fasta_index` and `cram_ref_fasta`

These are GCS pathnames
of the reference FASTA
to which the input CRAM is aligned.
This FASTA is used
to expand CRAMs to BAMs
and again is generally shared
across all the workflows.

#### `dbsnp_vcf_index` and `dbsnp_vcf`

These are GCS pathnames
of a VCF
containing a database
of known variants
from the reference.
As with the contamination
and reference FASTA files,
typically these are shared
across all the workflows.

#### `input_cram`

This is a GCS pathname to the input CRAM.
It's last component
will typically be the `base_file_name` value
with `".cram"` appended.
The `GDCWholeGenomeSomaticSingleSample.wdl` workflow definition
expects to find a `base_file_name.cram.crai` file
for every `base_file_name.cram` file
specified as an `input_cram`.

## Usage

GDCWholeGenomeSomaticSingleSample workload supports the following API endpoints:

| Verb | Endpoint                            | Description                                                    |
|------|-------------------------------------|----------------------------------------------------------------|
| GET  | `/api/v1/workload`                  | List all workloads, optionally filtering by uuid or project    |
| GET  | `/api/v1/workload/{uuid}/workflows` | List all workflows for a specified workload uuid               |
| POST | `/api/v1/create`                    | Create a new workload                                          |
| POST | `/api/v1/start`                     | Start a workload                                               |
| POST | `/api/v1/stop`                      | Stop a running workload                                        |
| POST | `/api/v1/exec`                      | Create and start (execute) a workload                          |

???+ warning "Permissions in production"
    External Whole Genome Reprocessing in `gotc-prod`
    uses a set of execution projects.
    Please refer to
    [this page](https://github.com/broadinstitute/gotc-deploy/blob/master/deploy/gotc-prod/helm/WFL_README.md)
    when you have questions about permissions.

### Create Workload: `/api/v1/create`

Create a WFL workload running in production.

=== "Request"

```json
curl --location --request POST \
https://gotc-prod-wfl.gotc-prod.broadinstitute.org/api/v1/create \
--header "Authorization: Bearer $(gcloud auth print-access-token)" \
--header 'Content-Type: application/json' \
--data-raw '
{
  "executor": "https://cromwell-gotc-auth.gotc-prod.broadinstitute.org",
  "output": "gs://broad-prod-somatic-genomes-output",
  "pipeline": "GDCWholeGenomeSomaticSingleSample",
  "project": "PO-1234",
  "items": [
    {
      "inputs": {
        "base_file_name": "27B-6",
        "contamination_vcf": "gs://gatk-best-practices/somatic-hg38/small_exac_common_3.hg38.vcf.gz",
        "contamination_vcf_index": "gs://gatk-best-practices/somatic-hg38/small_exac_common_3.hg38.vcf.gz.tbi",
        "cram_ref_fasta": "gs://gcp-public-data--broad-references/hg38/v0/Homo_sapiens_assembly38.fasta",
        "cram_ref_fasta_index": "gs://gcp-public-data--broad-references/hg38/v0/Homo_sapiens_assembly38.fasta.fai",
        "dbsnp_vcf": "gs://gcp-public-data--broad-references/hg38/v0/gdc/dbsnp_144.hg38.vcf.gz",
        "dbsnp_vcf_index": "gs://gcp-public-data--broad-references/hg38/v0/gdc/dbsnp_144.hg38.vcf.gz.tbi",
        "input_cram": "gs://broad-gotc-prod-storage/pipeline/PO-1234/27B-6/v1/27B-6.cram"
      },
      "options": {
        "monitoring_script": "gs://broad-gotc-prod-storage/scripts/monitoring_script.sh"
      }
    }
  ]
}'
```

=== "Response"

```json
{
  "commit": "477bb195c40cc5f5afb81ca1b57e97c9cc18fa2c",
  "created": "2021-04-05T16:02:31Z",
  "creator": "tbl@broadinstitute.org",
  "executor": "https://cromwell-gotc-auth.gotc-prod.broadinstitute.org",
  "output": "gs://broad-prod-somatic-genomes-output",
  "pipeline": "GDCWholeGenomeSomaticSingleSample",
  "project": "PO-1234",
  "release": "GDCWholeGenomeSomaticSingleSample_v1.1.0",
  "started": "2021-04-05T16:02:32Z",
  "uuid": "efb00901-378e-4365-86e7-edd0fbdaaab2",
  "version": "0.7.0",
  "wdl": "pipelines/broad/dna_seq/somatic/single_sample/wgs/gdc_genome/GDCWholeGenomeSomaticSingleSample.wdl"
}
```

Note that the `GDCWholeGenomeSomaticSingleSample` pipeline
supports Cromwell `workflowOptions`
via the `options` map.
See the [reference page](./usage-workflow-options)
for more information.

### Start Workload: `/api/v1/start`

Start all the workflows in the workload.

=== "Request"

```bash
curl --location --request POST \
https://gotc-prod-wfl.gotc-prod.broadinstitute.org/api/v1/start \
--header "Authorization: Bearer $(gcloud auth print-access-token)" \
--header 'Content-Type: application/json' \
--data-raw '{"uuid": "efb00901-378e-4365-86e7-edd0fbdaaab2"}'
```

=== "Response"

```json
{
  "commit": "477bb195c40cc5f5afb81ca1b57e97c9cc18fa2c",
  "created": "2021-04-05T16:02:31Z",
  "creator": "tbl@broadinstitute.org",
  "executor": "https://cromwell-gotc-auth.gotc-prod.broadinstitute.org",
  "output": "gs://broad-prod-somatic-genomes-output",
  "pipeline": "GDCWholeGenomeSomaticSingleSample",
  "project": "PO-1234",
  "release": "GDCWholeGenomeSomaticSingleSample_v1.1.0",
  "started": "2021-04-05T16:02:32Z",
  "uuid": "efb00901-378e-4365-86e7-edd0fbdaaab2",
  "version": "0.7.0",
  "wdl": "pipelines/broad/dna_seq/somatic/single_sample/wgs/gdc_genome/GDCWholeGenomeSomaticSingleSample.wdl"
}
```

### Start Workload: `/api/v1/start`

Included for compatibility with continuous workloads.

=== "Request"

```bash
curl -X POST 'https://gotc-prod-wfl.gotc-prod.broadinstitute.org/api/v1/stop' \
     -X "Authorization: Bearer $(gcloud auth print-access-token)" \
     -X 'Content-Type: application/json' \
     -d '{ "uuid": "efb00901-378e-4365-86e7-edd0fbdaaab2" }'
```

=== "Response"

```json
{
  "commit": "477bb195c40cc5f5afb81ca1b57e97c9cc18fa2c",
  "created": "2021-04-05T16:02:31Z",
  "creator": "tbl@broadinstitute.org",
  "executor": "https://cromwell-gotc-auth.gotc-prod.broadinstitute.org",
  "output": "gs://broad-prod-somatic-genomes-output",
  "pipeline": "GDCWholeGenomeSomaticSingleSample",
  "project": "PO-1234",
  "release": "GDCWholeGenomeSomaticSingleSample_v1.1.0",
  "started": "2021-04-05T16:02:32Z",
  "stopped": "2021-04-05T16:02:33Z",
  "uuid": "efb00901-378e-4365-86e7-edd0fbdaaab2",
  "version": "0.7.0",
  "wdl": "pipelines/broad/dna_seq/somatic/single_sample/wgs/gdc_genome/GDCWholeGenomeSomaticSingleSample.wdl"
}
```

### Exec Workload: `/api/v1/exec`

Create a workload,
then start every workflow
in the workload.

Except for the different WFL URI,
the request and response are the same
as for *Create Workload* above.

```bash
curl --location --request POST \
https://gotc-prod-wfl.gotc-prod.broadinstitute.org/api/v1/exec \
... and so on ...
```

### Query Workload: `/api/v1/workload?uuid=<uuid>`

Query WFL for a workload by its UUID.

=== "Request"

```bash
curl --location --request GET \
https://gotc-prod-wfl.gotc-prod.broadinstitute.org/api/v1/workload?uuid=efb00901-378e-4365-86e7-edd0fbdaaab2 \
--header 'Authorization: Bearer '$(gcloud auth print-access-token)
```

=== "Response"

A successful response from `/api/v1/workload`
is always an array of workload objects,
but specifying a UUID returns only one.

```json
[
 {
   "commit": "477bb195c40cc5f5afb81ca1b57e97c9cc18fa2c",
   "created": "2021-04-05T16:02:31Z",
   "creator": "tbl@broadinstitute.org",
   "executor": "https://cromwell-gotc-auth.gotc-prod.broadinstitute.org",
   "output": "gs://broad-prod-somatic-genomes-output",
   "pipeline": "GDCWholeGenomeSomaticSingleSample",
   "project": "PO-1234",
   "release": "GDCWholeGenomeSomaticSingleSample_v1.1.0",
   "started": "2021-04-05T16:02:32Z",
   "uuid": "efb00901-378e-4365-86e7-edd0fbdaaab2",
   "version": "0.7.0",
   "wdl": "pipelines/broad/dna_seq/somatic/single_sample/wgs/gdc_genome/GDCWholeGenomeSomaticSingleSample.wdl"
 }
]
```

### Query Workload with project: `/api/v1/workload?project=<project>`

Query WFL for all workloads
with a specified `project` label.

```bash
curl --location --request GET \
/api/v1/workload?project=wgs-dev \
https://gotc-prod-wfl.gotc-prod.broadinstitute.org/api/v1/workload?project=PO-1234 \
--header 'Authorization: Bearer '$(gcloud auth print-access-token)
```

The response is the same as when specifying a UUID,
except the array may contain multiple workload objects
that share the same `project` value.

!!! warning "Note"
    A request to the `/api/v1/workload` endpoint
    without a `project` or `uuid` parameter
    returns all of the workloads
    that WFL knows about.
    That response might be large
    and take a while to process.


### List workflows managed by the workload `GET /api/v1/workload/{uuid}/workflows`

=== "Request"

```bash
curl -X GET '/api/v1/workload/efb00901-378e-4365-86e7-edd0fbdaaab2/workflows' \
     -H 'Authorization: Bearer '$(gcloud auth print-access-token)
```

=== "Response"

A successful response from `/api/v1/workload/{uuid}/workload`
is always an array of Cromwell workflows with their statuses.

```json
[{
      "status": "Submitted",
      "updated": "2021-04-05T16:02:32Z",
      "uuid": "8c1f586e-036b-4690-87c2-2af5d7e00450",
      "inputs": {
          "base_file_name": "27B-6",
          "contamination_vcf": "gs://gatk-best-practices/somatic-hg38/small_exac_common_3.hg38.vcf.gz",
          "contamination_vcf_index": "gs://gatk-best-practices/somatic-hg38/small_exac_common_3.hg38.vcf.gz.tbi",
          "cram_ref_fasta": "gs://gcp-public-data--broad-references/hg38/v0/Homo_sapiens_assembly38.fasta",
          "cram_ref_fasta_index": "gs://gcp-public-data--broad-references/hg38/v0/Homo_sapiens_assembly38.fasta.fai",
          "dbsnp_vcf": "gs://gcp-public-data--broad-references/hg38/v0/gdc/dbsnp_144.hg38.vcf.gz",
          "dbsnp_vcf_index": "gs://gcp-public-data--broad-references/hg38/v0/gdc/dbsnp_144.hg38.vcf.gz.tbi",
          "input_cram": "gs://broad-gotc-prod-storage/pipeline/PO-1234/27B-6/v1/27B-6.cram"
      },
      "options": {
          "monitoring_script": "gs://broad-gotc-prod-storage/scripts/monitoring_script.sh"
      }
}]
```

# GDCWholeGenomeSomaticSingleSample

WFL manages a Somatic Genome (SG) pipeline
to support Whole Genome Shotgun (WGS) analysis
of somatic samples
for the Genomic Data Commons (GDC).

The pipeline workflows are specified
in the Workflow Definition Language (WDL).
WFL reads the WDL file from the
[WARP](https://github.com/broadinstitute/warp#wdl-analysis-research-pipelines)
repository.
The version is coded in WFL's SG module
[here](https://github.com/broadinstitute/wfl/blob/develop/api/src/wfl/module/sg.clj#L34).

## Inputs

In addition to these standard workload request inputs:

- `executor` : URL of the Cromwell service
- `output`   : URL prefix for output files in Google Cloud Storage
- `pipeline` : literally `"GDCWholeGenomeSomaticSingleSample"`
- `project`  : some tracking label you can choose

A `GDCWholeGenomeSomaticSingleSample` workload
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

These are Google Cloud Storage (GCS) URLs
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

#### other inputs

The Cromwell workflows that WFL creates
for a `GDCWholeGenomeSomaticSingleSample` workload
have other inputs that WFL's `wfl.module.sg`
adds to Cromwell submission.
WFL exports the inputs above
because they are the ones most likely
to vary across workloads.

WFL also submits a default set
of Cromwell workflow options
that a `/create` or `/exec` request
can override.
See [this section](./usage-workflow-options)
for more guidance on workflow options.

## Usage

GDCWholeGenomeSomaticSingleSample workload supports the following API endpoints:

| Verb | Endpoint                           | Description                                                 |
|------|------------------------------------|-------------------------------------------------------------|
| GET  | `/api/v1/workload`                 | List all workloads, optionally filtering by UUID or project |
| GET  | `/api/v1/workload/$UUID/workflows` | List all workflows for workload with `$UUID`                |
| POST | `/api/v1/create`                   | Create a new workload                                       |
| POST | `/api/v1/start`                    | Start a workload                                            |
| POST | `/api/v1/stop`                     | Stop a running workload                                     |
| POST | `/api/v1/exec`                     | Create and start (execute) a workload                       |

### Permissions in production

External Whole Genome Reprocessing in `gotc-prod`
uses a set of execution projects.
Please refer to
[this page](https://github.com/broadinstitute/gotc-deploy/blob/master/deploy/gotc-prod/helm/WFL_README.md)
when you have questions about permissions.

### Create Workload: `/api/v1/create`

Create a WFL workload running in production.

Here is a `/create` request using `curl`.

```shell
curl --location --request POST \
https://gotc-prod-wfl.gotc-prod.broadinstitute.org/api/v1/create \
--header "Authorization: Bearer $(gcloud auth print-access-token)" \
--header 'Content-Type: application/json' \
--data-raw `
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
  }
'
```

Each JSON object in `"items"`
defines a Cromwell workflow
to be launched
when the workload is started.

And here is a successful response to a `/create` request.

```json
{
  "commit": "477bx195c40cc5f5afb81ca1b57e97c9cc18fa2c",
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

Start the workload
by submitting all of its workflows
to Cromwell.

Here is a `/start` request using `curl`.

```bash
curl --location --request POST \
https://gotc-prod-wfl.gotc-prod.broadinstitute.org/api/v1/start \
--header "Authorization: Bearer $(gcloud auth print-access-token)" \
--header 'Content-Type: application/json' \
--data-raw '{"uuid": "efb00901-378e-4365-86e7-edd0fbdaaab2"}'
```

A successful `/start` response looks like this.

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

### Stop Workload: `/api/v1/stop`

Request a workload to stop with a request like this.

```bash
curl -X POST 'https://gotc-prod-wfl.gotc-prod.broadinstitute.org/api/v1/stop' \
     -X "Authorization: Bearer $(gcloud auth print-access-token)" \
     -X 'Content-Type: application/json' \
     -d '{ "uuid": "efb00901-378e-4365-86e7-edd0fbdaaab2" }'
```

A successful `/stop` response looks something like this.

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

The `/exec` request combines the functions
of `/create` and `/start`.

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

### Query Workload: `/api/v1/workload?uuid=$UUID`

Query WFL for a workload by its UUID.

Here is a query request.

```bash
curl --location --request GET \
https://gotc-prod-wfl.gotc-prod.broadinstitute.org/api/v1/workload?uuid=efb00901-378e-4365-86e7-edd0fbdaaab2 \
--header 'Authorization: Bearer '$(gcloud auth print-access-token)
```

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

This asks WFL for all workloads
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


> A request to the `/api/v1/workload` endpoint
> without a `project` or `uuid` parameter
> returns all of the workloads
> that WFL knows about.
> That response might be large
> and take a while to process.

### List workflows managed by the workload `GET /api/v1/workload/$UUID/workflows`

This request returns the workflows
in the workload with UUID
`efb00901-378e-4365-86e7-edd0fbdaaab2/workflows`.

```bash
curl -X GET '/api/v1/workload/efb00901-378e-4365-86e7-edd0fbdaaab2/workflows' \
     -H 'Authorization: Bearer '$(gcloud auth print-access-token)
```

A successful response
from `/api/v1/workload/$UUID/workload`
is always an array of Cromwell workflows
with their statuses.

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

## Outputs

The principle outputs
from a `GDCWholeGenomeSomaticSingleSample` workflow in WDL
are a BAM file and index,
and the associated metrics files.
Cromwell writes those files to a well-known "folder"
in Google Cloud Storage.
That place is
`gs://broad-prod-somatic-genomes-output/GDCWholeGenomeSomaticSingleSample/`
in production.
However,
WFL adds some extra outputs
to support the Broad Genomics Platform (GP),
and to preserve analysis provenance
and support data discovery.

### use of Clio

[Clio](https://github.com/broadinstitute/clio#clio)
is a metadata manager for GP.
It tracks the location of sample and analysis data,
and answers location queries by common key fields
such as data type,
project,
sample alias,
version,
and so on.

WFL adds a BAM record to Clio
for each `GDCWholeGenomeSomaticSingleSample` workflow
that succeeds in Cromwell.
That record,
when retrieved from Clio,
typically looks like this.
The record records the outputs of the workflow,
and adds enough metadata to support later
location and aggregation of project results.

``` json
{
  "bai_path": "gs://broad-prod-somatic-genomes-output/GDCWholeGenomeSomaticSingleSample/510aa24c-b1a4-4478-aeab-b43c075cb034/call-gatk_applybqsr/EOMI-B21D-NB1-A-1-0-D-A82T-36.aln.mrkdp.bai",
  "bam_path": "gs://broad-prod-somatic-genomes-output/GDCWholeGenomeSomaticSingleSample/510aa24c-b1a4-4478-aeab-b43c075cb034/call-gatk_applybqsr/EOMI-B21D-NB1-A-1-0-D-A82T-36.aln.mrkdp.bam",
  "data_type": "WGS",
  "document_status": "Normal",
  "insert_size_metrics_path": "gs://broad-prod-somatic-genomes-output/GDCWholeGenomeSomaticSingleSample/510aa24c-b1a4-4478-aeab-b43c075cb034/call-collect_insert_size_metrics/EOMI-B21D-NB1-A-1-0-D-A82T-36.aln.mrkdp.insert_size_metrics",
  "location": "GCP",
  "project": "RP-2359",
  "sample_alias": "EOMI-B21D-NB1-A-1-0-D-A82T-36",
  "version": 1
}
```

The content of the Clio BAM record
has three sources:

- the workload inputs specified to WFL
- the workflow outputs produced by Cromwell
- the Clio CRAM record for the workflow's `input_cram` file

WFL supplements the workflow output,
as necessary,
with the result of a query
for the Clio CRAM record
when the workload completes.

There are a couple of more details
about what goes into the Clio BAM record
that grew out of requirements discovered
later in operation of the pipeline.

### other outputs

In addition to the Cromwell workflow outputs
as recorded in the final Clio BAM record,
WFL produces two extra output files
for each successful Cromwell workflow.
WFL writes those extra output files
alongside the files created by the workflow.
Those files are named:

- `clio-bam-record.json`
- `cromwell-metadata.json`

`clio-bam-record.json` is just a JSON file
containing the BAM record
submitted to Clio
for the workflow output files.
The file is a convenience
for GP's project tracking
and quality assurance processes.

Similarly,
`cromwell-metadata.json` is a JSON file
containing the Cromwell metadata
for the workflow that generated the output.
WFL writes that file to preserve analysis provenance
and support pipeline debugging and compliance.
WFL records its own version information
in Cromwell workflow labels
That label information,
together with the Docker image tags
that Cromwell records in the workflow metadata,
should be enough to answer questions
about the tool chain used
to create the output files.

## Reprocessing

A `GDCWholeGenomeSomaticSingleSample` workflow
takes a while to run,
so early in the pipeline design,
the need to avoid redundant processing
was heavily emphasized.
WFL implemented some guards
in the pipeline to detect when a sample
was submitted more than once
and to prevent _reprocessing_ samples.

Of course,
as with most data processing pipelines,
samples are often intentionally submitted multiple times
to fix problems detected in analysis
or to debug problems with the pipeline.
WFL added some late features
to support reprocessing better.

As described above,
WFL uses Clio to discover
some attributes of the input CRAMs
to propagate to the output BAM records
that it writes to Clio
when a workload finishes.

Clio has a data safety features
that make it difficult to accidently
overwrite the file location data it manages.
Overwriting a record could
cause Clio to lose track
of an important
or expensive storage resource.
For example,
every record type that Clio implements
includes a set of metadata values
that constitute a key for that record.
The key for a BAM record is this tuple.

- location (always `"GCP"`)
- project
- data type
- sample alias
- version

Clio requires each BAM record submission
to provide values for all of those key fields,
but rejects attempts
to write a second BAM record
with the same key.
Also notice that the inputs
to a `GDCWholeGenomeSomaticSingleSample` workflow
do not admit a version.
Thus WFL used to copy the version of the input CRAM
as the version for the new BAM record it wrote to Clio.
As a consequence,
processing the same input CRAM
through the pipeline a second time
caused Clio to reject the second output BAM.

WFL works around that problem
by detecting the rejection response
from Clio,
and using it to trigger a remediation.
When WFL detects a Clio _overwrite_ rejection,
it queries Clio for all BAM records matching
the key it has composed for a new BAM record.
The query WFL makes omits the version specified
in the input CRAM record
such that Clio returns all records
that match the new BAM key
up to but omitting the version number.
Then WFL looks for the latest version of BAM
that Clio has seen matching the project,
data type,
and sample alias,
and increments that number
to arrive at a version
that Clio will accept.

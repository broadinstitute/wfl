# ExternalWholeGenomeReprocessing workload

## Inputs
An `ExternalWholeGenomeReprocessing` workload
requires the following inputs
for each workflow in the workload.

- input_cram
- sample_name
- base_file_name
- final_gvcf_base_name
- unmapped_bam_suffix

The `input_cram` is the path to a file
relative to the `input` URL prefix
in the `workload` definition above.

The `input_cram` contains the `sample_name`
but we break it out into a separate input here
to avoid parsing every (often large) CRAM file.

The `base_file_name` is used to name result files.
The workflow uses the `base_file_name`
together with one or more filetype suffixes
to name intermediate and output files.
It is usually just the leaf name of `input_cram`
with the `.cram` extension removed.

The `final_gvcf_base_name` is the root
of the leaf name
of the pathname of the final VCF.
The final VCF will have some variant
of a `.vcf` suffix
added by the workflow WDL.

It is common for `base_file_name`
and `final_gvcf_base_name`
to be identical to `sample_name`.
If no `base_file_name` is specified
for any workflow in a workload,
`base_file_name` defaults to `sample_name`.
Likewise,
if no `final_gvcf_base_name` is specified
for any workflow in a workload,
then `final_gvcf_base_name`
also defaults to `sample_name`.

It is used to recover the filename resulting
from re-aligning a reverted CRAM file.
The `unmapped_bam_suffix`
is almost always `.unmapped.bam`,
so that is its default value
unless it is specified.

## Usage

###Create Workload: `/api/v1/create`
Creates a WFL workload. Before processing, confirm that the WFL and Cromwell service accounts have
at least read access to the input files.
```
curl --location --request POST 'https://dev-wfl.gotc-dev.broadinstitute.org/api/v1/create' \
--header 'Authorization: Bearer '$(gcloud auth print-access-token) \
--header 'Content-Type: application/json' \
--data-raw '{
  "cromwell": "https://cromwell-gotc-auth.gotc-dev.broadinstitute.org",
  "input": "gs://broad-gotc-dev-wfl-ptc-test-inputs/single_sample/plumbing/truth",
  "output": "gs://broad-gotc-dev-wfl-ptc-test-outputs/wgs-test-output/",
  "pipeline": "ExternalWholeGenomeReprocessing",
  "project": "PO-1234",
  "items": [{
          "input_cram": "develop/20k/NA12878_PLUMBING.cram",
          "sample_name": "TestSample1234"
          }]}'
```

###Start Workload: `/api/v1/start`
Starts a Cromwell workflow for each item in the workload. If an output already exists in the output bucket for a
particular input cram, WFL will not re-submit that workflow.

```
curl --location --request POST 'https://dev-wfl.gotc-dev.broadinstitute.org/api/v1/start' \
--header 'Authorization: Bearer '$(gcloud auth print-access-token) \
--header 'Content-Type: application/json' \
--data-raw '[{"uuid": "74d96a04-fea7-4270-a02b-a319dae2dd5e"}]'
```

###Exec Workload: `/api/v1/exec`
Creates and then starts a Cromwell workflow for each item in the workload.
```
curl --location --request POST 'https://dev-wfl.gotc-dev.broadinstitute.org/api/v1/exec' \
--header 'Authorization: Bearer '$(gcloud auth print-access-token) \
--header 'Content-Type: application/json' \
--data-raw '{
  "cromwell": "https://cromwell-gotc-auth.gotc-dev.broadinstitute.org",
  "input": "gs://broad-gotc-dev-wfl-ptc-test-inputs/single_sample/plumbing/truth",
  "output": "gs://broad-gotc-dev-wfl-ptc-test-outputs/wgs-test-output/",
  "pipeline": "ExternalWholeGenomeReprocessing",
  "project": "PO-1234",
  "items": [{
          "input_cram": "develop/20k/NA12878_PLUMBING.cram",
          "sample_name": "TestSample1234"
          }]}'
```

###Query Workload: `/api/v1/workload?uuid=<uuid>`
Queries the WFL database for workloads. Specify the uuid to query for a specific workload.
```
curl --location --request GET 'https://dev-wfl.gotc-dev.broadinstitute.org/api/v1/workload?uuid=813e3c38-9c11-4410-9888-435569d91d1d' \
--header 'Authorization: Bearer '$(gcloud auth print-access-token)
```

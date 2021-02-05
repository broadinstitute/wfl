# External Exome Reprocessing workload

## Inputs
An `ExternalExomeReprocessing` workload requires the specification of exactly
one the following inputs for each workflow:

- `input_bam`, OR
- `input_cram`

Where `input_bam` and `input_cram` are GS URLs of the file to reprocess. Note
that the `input_bam` and `input_cram` inputs should only be used with CRAM and
BAM files, respectively. All other WDL inputs are optional - see the output
below for all options.

## Usage

External Exome Reprocessing workload supports the following API endpoints:

| Verb | Endpoint                     | Description                                                    |
|------|------------------------------|----------------------------------------------------------------|
| GET  | `/api/v1/workload`           | List all workloads                                             |
| GET  | `/api/v1/workload/{uuid}`    | Query for a workload by its UUID                               |
| GET  | `/api/v1/workload/{project}` | Query for a workload by its Project name                       |
| POST | `/api/v1/create`             | Create a new workload                                          |
| POST | `/api/v1/start`              | Start a workload                                               |
| POST | `/api/v1/exec`               | Create and start (execute) a workload                          |

???+ warning "Permissions in production"
    External Exome Reprocessing in `gotc-prod` uses a set of execution projects, please refer to 
    [this page](https://github.com/broadinstitute/gotc-deploy/blob/master/deploy/gotc-prod/helm/WFL_README.md)
    when you have questions about permissions.

### Create Workload: `/api/v1/create`
Create a new workload. Ensure that `workflow-launcher` and `cromwell`'s service
accounts have at least read access to the input files.

=== "Request"

    ```bash
    curl -X POST 'https://gotc-prod-wfl.gotc-prod.broadinstitute.org/api/v1/create' \
      -H "Authorization: Bearer $(gcloud auth print-access-token)" \
      -H 'Content-Type: application/json' \
      -d '{
            "executor": "https://cromwell-gotc-auth.gotc-prod.broadinstitute.org",
            "output": "gs://broad-gotc-dev-wfl-ptc-test-outputs/xx-test-output/",
            "pipeline": "ExternalExomeReprocessing",
            "project": "Example Project",
            "items": [{
              "inputs": {
                "input_cram" : "gs://broad-gotc-dev-wfl-ptc-test-inputs/single_sample/plumbing/truth/develop/20k/NA12878_PLUMBING.cram"
              }
            }]
          }'
    ```

=== "Response"

    ```json
    {
      "creator" : "user@domain",
      "pipeline" : "ExternalExomeReprocessing",
      "executor" : "https://cromwell-gotc-auth.gotc-prod.broadinstitute.org",
      "release" : "ExternalExomeReprocessing_vX.Y.Z",
      "created" : "YYYY-MM-DDTHH:MM:SSZ",
      "output" : "gs://broad-gotc-dev-wfl-ptc-test-outputs/xx-test-output/",
      "workflows" : [ {
        "inputs" : {
          "input_cram" : "gs://broad-gotc-dev-wfl-ptc-test-inputs/single_sample/plumbing/truth/develop/20k/NA12878_PLUMBING.cram",
          "destination_cloud_path" : "gs://broad-gotc-dev-wfl-ptc-test-outputs/xx-test-output/8600be1a-48df-4a51-bdba-0044e0af8d33/single_sample/plumbing/truth/develop/20k",
          "sample_name" : "NA12878_PLUMBING",
          "base_file_name" : "NA12878_PLUMBING.cram",
          "final_gvcf_base_name" : "NA12878_PLUMBING.cram"
        }
      } ],
      "commit" : "commit-ish",
      "project" : "Example Project",
      "uuid" : "1337254e-f7d8-438d-a2b3-a74b199fee3c",
      "wdl" : "pipelines/broad/reprocessing/external/exome/ExternalExomeReprocessing.wdl",
      "version" : "X.Y.Z"
    }
    ```

Note that the ExternalExomeReprocessing pipeline supports specifying cromwell
"workflowOptions" via the `options` map. See the
[reference page](./usage-workflow-options) for more information.

### Start Workload: `/api/v1/start`

Starts a Cromwell workflow for each item in the workload. If an output already exists in the output bucket for a
particular input cram, WFL will not re-submit that workflow.

=== "Request"

    ```bash
    curl -X POST 'https://gotc-prod-wfl.gotc-prod.broadinstitute.org/api/v1/start' \
    -H "Authorization: Bearer $(gcloud auth print-access-token)" \
    -H 'Content-Type: application/json' \
    -d '{"uuid": "1337254e-f7d8-438d-a2b3-a74b199fee3c"}'
    ```

=== "Response"

    ```json
    {
      "creator" : "user@domain",
      "pipeline" : "ExternalExomeReprocessing",
      "executor" : "https://cromwell-gotc-auth.gotc-prod.broadinstitute.org",
      "release" : "ExternalExomeReprocessing_vX.Y.Z",
      "created" : "YYYY-MM-DDTHH:MM:SSZ",
      "started" : "YYYY-MM-DDTHH:MM:SSZ",
      "output" : "gs://broad-gotc-dev-wfl-ptc-test-outputs/xx-test-output/",
      "workflows" : [ {
        "status" : "Submitted",
        "updated" : "YYYY-MM-DDTHH:MM:SSZ",
        "uuid" : "bb0d93e3-1a6a-4816-82d9-713fa58fb235",
        "inputs" : {
          "input_cram" : "gs://broad-gotc-dev-wfl-ptc-test-inputs/single_sample/plumbing/truth/develop/20k/NA12878_PLUMBING.cram",
          "destination_cloud_path" : "gs://broad-gotc-dev-wfl-ptc-test-outputs/xx-test-output/8600be1a-48df-4a51-bdba-0044e0af8d33/single_sample/plumbing/truth/develop/20k",
          "sample_name" : "NA12878_PLUMBING",
          "base_file_name" : "NA12878_PLUMBING.cram",
          "final_gvcf_base_name" : "NA12878_PLUMBING.cram"
        }
      } ],
      "commit" : "commit-ish",
      "project" : "Example Project",
      "uuid" : "1337254e-f7d8-438d-a2b3-a74b199fee3c",
      "wdl" : "pipelines/broad/reprocessing/external/exome/ExternalExomeReprocessing.wdl",
      "version" : "X.Y.Z"
    }
    ```

### Exec Workload: `/api/v1/exec`

Creates and then starts a Cromwell workflow for each item in the workload.

=== "Request"

    ```bash
    curl -X POST 'https://dev-wfl.gotc-dev.broadinstitute.org/api/v1/exec' \
      -H "Authorization: Bearer $(gcloud auth print-access-token)" \
      -H 'Content-Type: application/json' \
      -d '{
            "executor": "https://cromwell-gotc-auth.gotc-prod.broadinstitute.org",
            "output": "gs://broad-gotc-dev-wfl-ptc-test-outputs/xx-test-output/",
            "pipeline": "ExternalExomeReprocessing",
            "project": "Example Project",
            "items": [{
              "inputs" : {
                "input_cram" : "gs://broad-gotc-dev-wfl-ptc-test-inputs/single_sample/plumbing/truth/develop/20k/NA12878_PLUMBING.cram",
              }
            }]
          }'
    ```

=== "Response"

    ```json
    {
      "creator" : "user@domain",
      "pipeline" : "ExternalExomeReprocessing",
      "executor" : "https://cromwell-gotc-auth.gotc-prod.broadinstitute.org",
      "release" : "ExternalExomeReprocessing_vX.Y.Z",
      "created" : "YYYY-MM-DDTHH:MM:SSZ",
      "started" : "YYYY-MM-DDTHH:MM:SSZ",
      "output" : "gs://broad-gotc-dev-wfl-ptc-test-outputs/xx-test-output/",
      "workflows" : [ {
        "status" : "Submitted",
        "updated" : "YYYY-MM-DDTHH:MM:SSZ",
        "uuid" : "bb0d93e3-1a6a-4816-82d9-713fa58fb235",
        "inputs" : {
            "input_cram" : "gs://broad-gotc-dev-wfl-ptc-test-inputs/single_sample/plumbing/truth/develop/20k/NA12878_PLUMBING.cram",
            "destination_cloud_path" : "gs://broad-gotc-dev-wfl-ptc-test-outputs/xx-test-output/8600be1a-48df-4a51-bdba-0044e0af8d33/single_sample/plumbing/truth/develop/20k",
            "sample_name" : "NA12878_PLUMBING",
            "base_file_name" : "NA12878_PLUMBING.cram",
            "final_gvcf_base_name" : "NA12878_PLUMBING.cram"
          }
      } ],
      "commit" : "commit-ish",
      "project" : "Example Project",
      "uuid" : "1337254e-f7d8-438d-a2b3-a74b199fee3c",
      "wdl" : "pipelines/broad/reprocessing/external/exome/ExternalExomeReprocessing.wdl",
      "version" : "X.Y.Z"
    }
    ```

### Query Workload: `/api/v1/workload?uuid=<uuid>`

Queries the WFL database for workloads. Specify the uuid to query for a specific workload.

=== "Request"

    ```bash
    curl -X GET 'https://gotc-prod-wfl.gotc-prod.broadinstitute.org/api/v1/workload?uuid=1337254e-f7d8-438d-a2b3-a74b199fee3c' \
      -H "Authorization: Bearer $(gcloud auth print-access-token)"
    ```

=== "Response"

    ```json
    [{
      "creator" : "user@domain",
      "pipeline" : "ExternalExomeReprocessing",
      "executor" : "https://cromwell-gotc-auth.gotc-prod.broadinstitute.org",
      "release" : "ExternalExomeReprocessing_vX.Y.Z",
      "created" : "YYYY-MM-DDTHH:MM:SSZ",
      "started" : "YYYY-MM-DDTHH:MM:SSZ",
      "output" : "gs://broad-gotc-dev-wfl-ptc-test-outputs/xx-test-output/",
      "workflows" : [ {
        "status" : "Submitted",
        "updated" : "YYYY-MM-DDTHH:MM:SSZ",
        "uuid" : "bb0d93e3-1a6a-4816-82d9-713fa58fb235",
        "inputs" : {
          "input_cram" : "gs://broad-gotc-dev-wfl-ptc-test-inputs/single_sample/plumbing/truth/develop/20k/NA12878_PLUMBING.cram",
          "destination_cloud_path" : "gs://broad-gotc-dev-wfl-ptc-test-outputs/xx-test-output/8600be1a-48df-4a51-bdba-0044e0af8d33/single_sample/plumbing/truth/develop/20k",
          "sample_name" : "NA12878_PLUMBING",
          "base_file_name" : "NA12878_PLUMBING.cram",
          "final_gvcf_base_name" : "NA12878_PLUMBING.cram"
        }
      } ],
      "commit" : "commit-ish",
      "project" : "Example Project",
      "uuid" : "1337254e-f7d8-438d-a2b3-a74b199fee3c",
      "wdl" : "pipelines/broad/reprocessing/external/exome/ExternalExomeReprocessing.wdl",
      "version" : "X.Y.Z"
    }]
    ```

The "workflows" field lists out each Cromwell workflow that was started, and
includes their status information. It is also possible to use the Job Manager
to check workflow progress and easily see information about any workflow
failures.

### Query Workload with project: `/api/v1/workload?project=<project>`

Queries the WFL database for workloads. Specify the project name to query for a list of specific workload(s).

=== "Request"

    ```bash
    curl -X GET 'https://gotc-prod-wfl.gotc-prod.broadinstitute.org/api/v1/workload?project=PO-1234' \
      -H "Authorization: Bearer $(gcloud auth print-access-token)"
    ```

=== "Response"

    ```json
    [{
      "creator" : "user@domain",
      "pipeline" : "ExternalExomeReprocessing",
      "executor" : "https://cromwell-gotc-auth.gotc-prod.broadinstitute.org",
      "release" : "ExternalExomeReprocessing_vX.Y.Z",
      "created" : "YYYY-MM-DDTHH:MM:SSZ",
      "started" : "YYYY-MM-DDTHH:MM:SSZ",
      "output" : "gs://broad-gotc-dev-wfl-ptc-test-outputs/xx-test-output/",
      "workflows" : [ {
        "status" : "Submitted",
        "updated" : "YYYY-MM-DDTHH:MM:SSZ",
        "uuid" : "bb0d93e3-1a6a-4816-82d9-713fa58fb235",
        "inputs" : {
          "input_cram" : "gs://broad-gotc-dev-wfl-ptc-test-inputs/single_sample/plumbing/truth/develop/20k/NA12878_PLUMBING.cram",
          "destination_cloud_path" : "gs://broad-gotc-dev-wfl-ptc-test-outputs/xx-test-output/8600be1a-48df-4a51-bdba-0044e0af8d33/single_sample/plumbing/truth/develop/20k",
          "sample_name" : "NA12878_PLUMBING",
          "base_file_name" : "NA12878_PLUMBING.cram",
          "final_gvcf_base_name" : "NA12878_PLUMBING.cram"
        }
      } ],
      "commit" : "commit-ish",
      "project" : "Example Project",
      "uuid" : "1337254e-f7d8-438d-a2b3-a74b199fee3c",
      "wdl" : "pipelines/broad/reprocessing/external/exome/ExternalExomeReprocessing.wdl",
      "version" : "X.Y.Z"
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

## A1 External Exome Workload-Request JSON Spec
```json
{
     "pipeline": "string",
     "executor": "string",
     "output": "string",
     "project": "string",
     "common": {
       "options": {}, // see workflow-options
       "inputs": {
         "unmapped_bam_suffix":    "string",
         "cram_ref_fasta":       "string",
         "cram_ref_fasta_index": "string",
         "bait_set_name":        "string",
         "bait_interval_list":   "string",
         "target_interval_list": "string",
         "references": {
           "calling_interval_list":      "string",
           "contamination_sites_bed":    "string",
           "contamination_sites_mu":     "string",
           "contamination_sites_ud":     "string",
           "dbsnp_vcf":                  "string",
           "dbsnp_vcf_index":            "string",
           "evaluation_interval_list":   "string",
           "haplotype_database_file":     "string",
           "known_indels_sites_vcfs":    ["string"],
           "known_indels_sites_indices": ["string"],
           "reference_fasta": {
             "ref_pac":         "string",
             "ref_bwt":         "string",
             "ref_dict":        "string",
             "ref_ann":         "string",
             "ref_fasta_index": "string",
             "ref_alt":         "string",
             "ref_fasta":       "string",
             "ref_sa":          "string",
             "ref_amb":         "string"
           }
         },
         "scatter_settings": {
           "haplotype_scatter_count":     "integer",
           "break_bands_at_multiples_of": "integer"
         },
         "papi_settings": {
           "agg_preemptible_tries": "integer",
           "preemptible_tries":     "integer"
         },
         "fingerprint_genotypes_file": "string",
         "fingerprint_genotypes_index": "string"
       }
     },
     "items": [{
         "options": {}, // see workflow-options
         "inputs": {
             // required - specify either "input_bam" or "input_cram"
             "input_bam":  "string",
             "input_cram": "string",

             // optional inputs
             "sample_name":          "string",
             "final_gvcf_base_name":  "string",
             "unmapped_bam_suffix":    "string",
             "cram_ref_fasta":       "string",
             "cram_ref_fasta_index": "string",
             "bait_set_name":        "string",
             "bait_interval_list":   "string",
             "target_interval_list": "string",
             "references": {
                 "calling_interval_list":      "string",
                 "contamination_sites_bed":    "string",
                 "contamination_sites_mu":     "string",
                 "contamination_sites_ud":     "string",
                 "dbsnp_vcf":                  "string",
                 "dbsnp_vcf_index":            "string",
                 "evaluation_interval_list":   "string",
                 "haplotype_database_file":     "string",
                 "known_indels_sites_vcfs":    ["string"],
                 "known_indels_sites_indices": ["string"],
                 "reference_fasta": {
                     "ref_pac":         "string",
                     "ref_bwt":         "string",
                     "ref_dict":        "string",
                     "ref_ann":         "string",
                     "ref_fasta_index": "string",
                     "ref_alt":         "string",
                     "ref_fasta":       "string",
                     "ref_sa":          "string",
                     "ref_amb":         "string"
                 }
             },
             "scatter_settings": {
                 "haplotype_scatter_count":     "integer",
                 "break_bands_at_multiples_of": "integer"
             },
             "papi_settings": {
                 "agg_preemptible_tries": "integer",
                 "preemptible_tries":     "integer"
             },
             "fingerprint_genotypes_file":  "string",
             "fingerprint_genotypes_index": "string",
             "destination_cloud_path":      "string"
         }
     }]
}
```

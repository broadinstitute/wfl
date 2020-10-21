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

###Create Workload: `/api/v1/create`
Create a new workload. Ensure that `workflow-launcher` and `cromwell`'s service
accounts have at least read access to the input files.

Request:
```
curl -X POST 'https://workflow-launcher.broadinstitute.org/api/v1/create' \
  -H "Authorization: Bearer $(gcloud auth print-access-token)" \
  -H 'Content-Type: application/json' \
  -d '{
        "cromwell": "https://cromwell-gotc-auth.gotc-dev.broadinstitute.org",
        "output": "gs://broad-gotc-dev-wfl-ptc-test-outputs/xx-test-output/",
        "pipeline": "ExternalExomeReprocessing",
        "project": "PO-1234",
        "items": [{
          "input_cram": "gs://path/to/a.cram"
        }]
      }'
```
Response:
```
{
  "creator" : "user@domain",
  "pipeline" : "ExternalExomeReprocessing",
  "cromwell" : "https://cromwell-gotc-auth.gotc-dev.broadinstitute.org",
  "release" : "ExternalExomeReprocessing_vX.Y.Z",
  "created" : "YYYY-MM-DDTHH:MM:SSZ",
  "output" : "gs://broad-gotc-dev-wfl-ptc-test-outputs/xx-test-output/",
  "workflows" : [ {
    "inputs" : {
      "cram_ref_fasta" : "gs://gcp-public-data--broad-references/hg38/v0/Homo_sapiens_assembly38.fasta",
      "scatter_settings" : {
        "haplotype_scatter_count" : 50,
        "break_bands_at_multiples_of" : 0
      },
      "input_cram" : "gs://path/to/a.cram",
      "destination_cloud_path" : "gs://broad-gotc-dev-wfl-ptc-test-outputs/xx-test-output/to",
      "sample_name" : "a",
      "bait_set_name" : "whole_exome_illumina_coding_v1",
      "references" : {
        "calling_interval_list" : "gs://gcp-public-data--broad-references/hg38/v0/exome_calling_regions.v1.interval_list",
        "contamination_sites_bed" : "gs://gcp-public-data--broad-references/hg38/v0/contamination-resources/1000g/1000g.phase3.100k.b38.vcf.gz.dat.bed",
        "dbsnp_vcf_index" : "gs://gcp-public-data--broad-references/hg38/v0/Homo_sapiens_assembly38.dbsnp138.vcf.idx",
        "haplotype_database_file" : "gs://gcp-public-data--broad-references/hg38/v0/Homo_sapiens_assembly38.haplotype_database.txt",
        "known_indels_sites_indices" : [ "gs://gcp-public-data--broad-references/hg38/v0/Mills_and_1000G_gold_standard.indels.hg38.vcf.gz.tbi", "gs://gcp-public-data--broad-references/hg38/v0/Homo_sapiens_assembly38.known_indels.vcf.gz.tbi" ],
        "evaluation_interval_list" : "gs://gcp-public-data--broad-references/hg38/v0/exome_evaluation_regions.v1.interval_list",
        "contamination_sites_mu" : "gs://gcp-public-data--broad-references/hg38/v0/contamination-resources/1000g/1000g.phase3.100k.b38.vcf.gz.dat.mu",
        "dbsnp_vcf" : "gs://gcp-public-data--broad-references/hg38/v0/Homo_sapiens_assembly38.dbsnp138.vcf",
        "known_indels_sites_vcfs" : [ "gs://gcp-public-data--broad-references/hg38/v0/Mills_and_1000G_gold_standard.indels.hg38.vcf.gz", "gs://gcp-public-data--broad-references/hg38/v0/Homo_sapiens_assembly38.known_indels.vcf.gz" ],
        "reference_fasta" : {
          "ref_pac" : "gs://gcp-public-data--broad-references/hg38/v0/Homo_sapiens_assembly38.fasta.64.pac",
          "ref_bwt" : "gs://gcp-public-data--broad-references/hg38/v0/Homo_sapiens_assembly38.fasta.64.bwt",
          "ref_dict" : "gs://gcp-public-data--broad-references/hg38/v0/Homo_sapiens_assembly38.dict",
          "ref_ann" : "gs://gcp-public-data--broad-references/hg38/v0/Homo_sapiens_assembly38.fasta.64.ann",
          "ref_fasta_index" : "gs://gcp-public-data--broad-references/hg38/v0/Homo_sapiens_assembly38.fasta.fai",
          "ref_alt" : "gs://gcp-public-data--broad-references/hg38/v0/Homo_sapiens_assembly38.fasta.64.alt",
          "ref_fasta" : "gs://gcp-public-data--broad-references/hg38/v0/Homo_sapiens_assembly38.fasta",
          "ref_sa" : "gs://gcp-public-data--broad-references/hg38/v0/Homo_sapiens_assembly38.fasta.64.sa",
          "ref_amb" : "gs://gcp-public-data--broad-references/hg38/v0/Homo_sapiens_assembly38.fasta.64.amb"
        },
        "contamination_sites_ud" : "gs://gcp-public-data--broad-references/hg38/v0/contamination-resources/1000g/1000g.phase3.100k.b38.vcf.gz.dat.UD"
      },
      "cram_ref_fasta_index" : "gs://gcp-public-data--broad-references/hg38/v0/Homo_sapiens_assembly38.fasta.fai",
      "papi_settings" : {
        "agg_preemptible_tries" : 3,
        "preemptible_tries" : 3
      },
      "base_file_name" : "a.cram",
      "bait_interval_list" : "gs://gcp-public-data--broad-references/hg38/v0/HybSelOligos/whole_exome_illumina_coding_v1/whole_exome_illumina_coding_v1.Homo_sapiens_assembly38.baits.interval_list",
      "final_gvcf_base_name" : "a.cram",
      "target_interval_list" : "gs://gcp-public-data--broad-references/hg38/v0/HybSelOligos/whole_exome_illumina_coding_v1/whole_exome_illumina_coding_v1.Homo_sapiens_assembly38.targets.interval_list",
      "unmapped_bam_suffix" : ".unmapped.bam"
    }
  } ],
  "commit" : "commit-ish",
  "project" : "PO-1234",  
  "uuid" : "1337254e-f7d8-438d-a2b3-a74b199fee3c",  
  "wdl" : "pipelines/broad/reprocessing/external/exome/ExternalExomeReprocessing.wdl",
  "version" : "X.Y.Z"
}
```

###Start Workload: `/api/v1/start`
Starts a Cromwell workflow for each item in the workload. If an output already exists in the output bucket for a
particular input cram, WFL will not re-submit that workflow.

Request:
```
curl -X POST 'https://workflow-launcher.broadinstitute.org/api/v1/start' \
 -H "Authorization: Bearer $(gcloud auth print-access-token)" \
 -H 'Content-Type: application/json' \
 -d '[{"uuid": "1337254e-f7d8-438d-a2b3-a74b199fee3c"}]'
```
Response:
```
[{
   "creator" : "user@domain",
   "pipeline" : "ExternalExomeReprocessing",
   "cromwell" : "https://cromwell-gotc-auth.gotc-dev.broadinstitute.org",
   "release" : "ExternalExomeReprocessing_vX.Y.Z",
   "created" : "YYYY-MM-DDTHH:MM:SSZ",
   "started" : "YYYY-MM-DDTHH:MM:SSZ",
   "output" : "gs://broad-gotc-dev-wfl-ptc-test-outputs/xx-test-output/",
   "workflows" : [ {
     "status" : "Submitted"
     "updated" : "YYYY-MM-DDTHH:MM:SSZ",
     "uuid" : "bb0d93e3-1a6a-4816-82d9-713fa58fb235",
     "inputs" : {
       "cram_ref_fasta" : "gs://gcp-public-data--broad-references/hg38/v0/Homo_sapiens_assembly38.fasta",
       "scatter_settings" : {
         "haplotype_scatter_count" : 50,
         "break_bands_at_multiples_of" : 0
       },
       "input_cram" : "gs://path/to/a.cram",
       "destination_cloud_path" : "gs://broad-gotc-dev-wfl-ptc-test-outputs/xx-test-output/to",
       "sample_name" : "a",
       "bait_set_name" : "whole_exome_illumina_coding_v1",
       "references" : {
         "calling_interval_list" : "gs://gcp-public-data--broad-references/hg38/v0/exome_calling_regions.v1.interval_list",
         "contamination_sites_bed" : "gs://gcp-public-data--broad-references/hg38/v0/contamination-resources/1000g/1000g.phase3.100k.b38.vcf.gz.dat.bed",
         "dbsnp_vcf_index" : "gs://gcp-public-data--broad-references/hg38/v0/Homo_sapiens_assembly38.dbsnp138.vcf.idx",
         "haplotype_database_file" : "gs://gcp-public-data--broad-references/hg38/v0/Homo_sapiens_assembly38.haplotype_database.txt",
         "known_indels_sites_indices" : [ "gs://gcp-public-data--broad-references/hg38/v0/Mills_and_1000G_gold_standard.indels.hg38.vcf.gz.tbi", "gs://gcp-public-data--broad-references/hg38/v0/Homo_sapiens_assembly38.known_indels.vcf.gz.tbi" ],
         "evaluation_interval_list" : "gs://gcp-public-data--broad-references/hg38/v0/exome_evaluation_regions.v1.interval_list",
         "contamination_sites_mu" : "gs://gcp-public-data--broad-references/hg38/v0/contamination-resources/1000g/1000g.phase3.100k.b38.vcf.gz.dat.mu",
         "dbsnp_vcf" : "gs://gcp-public-data--broad-references/hg38/v0/Homo_sapiens_assembly38.dbsnp138.vcf",
         "known_indels_sites_vcfs" : [ "gs://gcp-public-data--broad-references/hg38/v0/Mills_and_1000G_gold_standard.indels.hg38.vcf.gz", "gs://gcp-public-data--broad-references/hg38/v0/Homo_sapiens_assembly38.known_indels.vcf.gz" ],
         "reference_fasta" : {
           "ref_pac" : "gs://gcp-public-data--broad-references/hg38/v0/Homo_sapiens_assembly38.fasta.64.pac",
           "ref_bwt" : "gs://gcp-public-data--broad-references/hg38/v0/Homo_sapiens_assembly38.fasta.64.bwt",
           "ref_dict" : "gs://gcp-public-data--broad-references/hg38/v0/Homo_sapiens_assembly38.dict",
           "ref_ann" : "gs://gcp-public-data--broad-references/hg38/v0/Homo_sapiens_assembly38.fasta.64.ann",
           "ref_fasta_index" : "gs://gcp-public-data--broad-references/hg38/v0/Homo_sapiens_assembly38.fasta.fai",
           "ref_alt" : "gs://gcp-public-data--broad-references/hg38/v0/Homo_sapiens_assembly38.fasta.64.alt",
           "ref_fasta" : "gs://gcp-public-data--broad-references/hg38/v0/Homo_sapiens_assembly38.fasta",
           "ref_sa" : "gs://gcp-public-data--broad-references/hg38/v0/Homo_sapiens_assembly38.fasta.64.sa",
           "ref_amb" : "gs://gcp-public-data--broad-references/hg38/v0/Homo_sapiens_assembly38.fasta.64.amb"
         },
         "contamination_sites_ud" : "gs://gcp-public-data--broad-references/hg38/v0/contamination-resources/1000g/1000g.phase3.100k.b38.vcf.gz.dat.UD"
       },
       "cram_ref_fasta_index" : "gs://gcp-public-data--broad-references/hg38/v0/Homo_sapiens_assembly38.fasta.fai",
       "papi_settings" : {
         "agg_preemptible_tries" : 3,
         "preemptible_tries" : 3
       },
       "base_file_name" : "a.cram",
       "bait_interval_list" : "gs://gcp-public-data--broad-references/hg38/v0/HybSelOligos/whole_exome_illumina_coding_v1/whole_exome_illumina_coding_v1.Homo_sapiens_assembly38.baits.interval_list",
       "final_gvcf_base_name" : "a.cram",
       "target_interval_list" : "gs://gcp-public-data--broad-references/hg38/v0/HybSelOligos/whole_exome_illumina_coding_v1/whole_exome_illumina_coding_v1.Homo_sapiens_assembly38.targets.interval_list",
       "unmapped_bam_suffix" : ".unmapped.bam"
     }
   } ],
   "commit" : "commit-ish",
   "project" : "PO-1234",  
   "uuid" : "1337254e-f7d8-438d-a2b3-a74b199fee3c",  
   "wdl" : "pipelines/broad/reprocessing/external/exome/ExternalExomeReprocessing.wdl",
   "version" : "X.Y.Z"
 }]
```

###Exec Workload: `/api/v1/exec`
Creates and then starts a Cromwell workflow for each item in the workload.

Request:
```
curl -X POST 'https://workflow-launcher.broadinstitute.org/api/v1/exec' \
  -H "Authorization: Bearer $(gcloud auth print-access-token)" \
  -H 'Content-Type: application/json' \
  -d '{
        "cromwell": "https://cromwell-gotc-auth.gotc-dev.broadinstitute.org",
        "output": "gs://broad-gotc-dev-wfl-ptc-test-outputs/xx-test-output/",
        "pipeline": "ExternalExomeReprocessing",
        "project": "PO-1234",
        "items": [{
          "input_cram": "gs://path/to/a.cram"
        }]
      }'
```
Response:
```
{
   "creator" : "user@domain",
   "pipeline" : "ExternalExomeReprocessing",
   "cromwell" : "https://cromwell-gotc-auth.gotc-dev.broadinstitute.org",
   "release" : "ExternalExomeReprocessing_vX.Y.Z",
   "created" : "YYYY-MM-DDTHH:MM:SSZ",
   "started" : "YYYY-MM-DDTHH:MM:SSZ",
   "output" : "gs://broad-gotc-dev-wfl-ptc-test-outputs/xx-test-output/",
   "workflows" : [ {
     "status" : "Submitted"
     "updated" : "YYYY-MM-DDTHH:MM:SSZ",
     "uuid" : "bb0d93e3-1a6a-4816-82d9-713fa58fb235",
     "inputs" : {
       "cram_ref_fasta" : "gs://gcp-public-data--broad-references/hg38/v0/Homo_sapiens_assembly38.fasta",
       "scatter_settings" : {
         "haplotype_scatter_count" : 50,
         "break_bands_at_multiples_of" : 0
       },
       "input_cram" : "gs://path/to/a.cram",
       "destination_cloud_path" : "gs://broad-gotc-dev-wfl-ptc-test-outputs/xx-test-output/to",
       "sample_name" : "a",
       "bait_set_name" : "whole_exome_illumina_coding_v1",
       "references" : {
         "calling_interval_list" : "gs://gcp-public-data--broad-references/hg38/v0/exome_calling_regions.v1.interval_list",
         "contamination_sites_bed" : "gs://gcp-public-data--broad-references/hg38/v0/contamination-resources/1000g/1000g.phase3.100k.b38.vcf.gz.dat.bed",
         "dbsnp_vcf_index" : "gs://gcp-public-data--broad-references/hg38/v0/Homo_sapiens_assembly38.dbsnp138.vcf.idx",
         "haplotype_database_file" : "gs://gcp-public-data--broad-references/hg38/v0/Homo_sapiens_assembly38.haplotype_database.txt",
         "known_indels_sites_indices" : [ "gs://gcp-public-data--broad-references/hg38/v0/Mills_and_1000G_gold_standard.indels.hg38.vcf.gz.tbi", "gs://gcp-public-data--broad-references/hg38/v0/Homo_sapiens_assembly38.known_indels.vcf.gz.tbi" ],
         "evaluation_interval_list" : "gs://gcp-public-data--broad-references/hg38/v0/exome_evaluation_regions.v1.interval_list",
         "contamination_sites_mu" : "gs://gcp-public-data--broad-references/hg38/v0/contamination-resources/1000g/1000g.phase3.100k.b38.vcf.gz.dat.mu",
         "dbsnp_vcf" : "gs://gcp-public-data--broad-references/hg38/v0/Homo_sapiens_assembly38.dbsnp138.vcf",
         "known_indels_sites_vcfs" : [ "gs://gcp-public-data--broad-references/hg38/v0/Mills_and_1000G_gold_standard.indels.hg38.vcf.gz", "gs://gcp-public-data--broad-references/hg38/v0/Homo_sapiens_assembly38.known_indels.vcf.gz" ],
         "reference_fasta" : {
           "ref_pac" : "gs://gcp-public-data--broad-references/hg38/v0/Homo_sapiens_assembly38.fasta.64.pac",
           "ref_bwt" : "gs://gcp-public-data--broad-references/hg38/v0/Homo_sapiens_assembly38.fasta.64.bwt",
           "ref_dict" : "gs://gcp-public-data--broad-references/hg38/v0/Homo_sapiens_assembly38.dict",
           "ref_ann" : "gs://gcp-public-data--broad-references/hg38/v0/Homo_sapiens_assembly38.fasta.64.ann",
           "ref_fasta_index" : "gs://gcp-public-data--broad-references/hg38/v0/Homo_sapiens_assembly38.fasta.fai",
           "ref_alt" : "gs://gcp-public-data--broad-references/hg38/v0/Homo_sapiens_assembly38.fasta.64.alt",
           "ref_fasta" : "gs://gcp-public-data--broad-references/hg38/v0/Homo_sapiens_assembly38.fasta",
           "ref_sa" : "gs://gcp-public-data--broad-references/hg38/v0/Homo_sapiens_assembly38.fasta.64.sa",
           "ref_amb" : "gs://gcp-public-data--broad-references/hg38/v0/Homo_sapiens_assembly38.fasta.64.amb"
         },
         "contamination_sites_ud" : "gs://gcp-public-data--broad-references/hg38/v0/contamination-resources/1000g/1000g.phase3.100k.b38.vcf.gz.dat.UD"
       },
       "cram_ref_fasta_index" : "gs://gcp-public-data--broad-references/hg38/v0/Homo_sapiens_assembly38.fasta.fai",
       "papi_settings" : {
         "agg_preemptible_tries" : 3,
         "preemptible_tries" : 3
       },
       "base_file_name" : "a.cram",
       "bait_interval_list" : "gs://gcp-public-data--broad-references/hg38/v0/HybSelOligos/whole_exome_illumina_coding_v1/whole_exome_illumina_coding_v1.Homo_sapiens_assembly38.baits.interval_list",
       "final_gvcf_base_name" : "a.cram",
       "target_interval_list" : "gs://gcp-public-data--broad-references/hg38/v0/HybSelOligos/whole_exome_illumina_coding_v1/whole_exome_illumina_coding_v1.Homo_sapiens_assembly38.targets.interval_list",
       "unmapped_bam_suffix" : ".unmapped.bam"
     }
   } ],
   "commit" : "commit-ish",
   "project" : "PO-1234",  
   "uuid" : "1337254e-f7d8-438d-a2b3-a74b199fee3c",  
   "wdl" : "pipelines/broad/reprocessing/external/exome/ExternalExomeReprocessing.wdl",
   "version" : "X.Y.Z"
 }
```

###Query Workload: `/api/v1/workload?uuid=<uuid>`
Queries the WFL database for workloads. Specify the uuid to query for a specific workload.

Request:
```
curl -X GET 'https://workflow-launcher.broadinstitute.org/api/v1/workload?uuid=1337254e-f7d8-438d-a2b3-a74b199fee3c' \
  -H "Authorization: Bearer $(gcloud auth print-access-token)"
```

Response:
```
[{
   "creator" : "user@domain",
   "pipeline" : "ExternalExomeReprocessing",
   "cromwell" : "https://cromwell-gotc-auth.gotc-dev.broadinstitute.org",
   "release" : "ExternalExomeReprocessing_vX.Y.Z",
   "created" : "YYYY-MM-DDTHH:MM:SSZ",
   "started" : "YYYY-MM-DDTHH:MM:SSZ",
   "output" : "gs://broad-gotc-dev-wfl-ptc-test-outputs/xx-test-output/",
   "workflows" : [ {
     "status" : "Submitted"
     "updated" : "YYYY-MM-DDTHH:MM:SSZ",
     "uuid" : "bb0d93e3-1a6a-4816-82d9-713fa58fb235",
     "inputs" : {
       "cram_ref_fasta" : "gs://gcp-public-data--broad-references/hg38/v0/Homo_sapiens_assembly38.fasta",
       "scatter_settings" : {
         "haplotype_scatter_count" : 50,
         "break_bands_at_multiples_of" : 0
       },
       "input_cram" : "gs://path/to/a.cram",
       "destination_cloud_path" : "gs://broad-gotc-dev-wfl-ptc-test-outputs/xx-test-output/to",
       "sample_name" : "a",
       "bait_set_name" : "whole_exome_illumina_coding_v1",
       "references" : {
         "calling_interval_list" : "gs://gcp-public-data--broad-references/hg38/v0/exome_calling_regions.v1.interval_list",
         "contamination_sites_bed" : "gs://gcp-public-data--broad-references/hg38/v0/contamination-resources/1000g/1000g.phase3.100k.b38.vcf.gz.dat.bed",
         "dbsnp_vcf_index" : "gs://gcp-public-data--broad-references/hg38/v0/Homo_sapiens_assembly38.dbsnp138.vcf.idx",
         "haplotype_database_file" : "gs://gcp-public-data--broad-references/hg38/v0/Homo_sapiens_assembly38.haplotype_database.txt",
         "known_indels_sites_indices" : [ "gs://gcp-public-data--broad-references/hg38/v0/Mills_and_1000G_gold_standard.indels.hg38.vcf.gz.tbi", "gs://gcp-public-data--broad-references/hg38/v0/Homo_sapiens_assembly38.known_indels.vcf.gz.tbi" ],
         "evaluation_interval_list" : "gs://gcp-public-data--broad-references/hg38/v0/exome_evaluation_regions.v1.interval_list",
         "contamination_sites_mu" : "gs://gcp-public-data--broad-references/hg38/v0/contamination-resources/1000g/1000g.phase3.100k.b38.vcf.gz.dat.mu",
         "dbsnp_vcf" : "gs://gcp-public-data--broad-references/hg38/v0/Homo_sapiens_assembly38.dbsnp138.vcf",
         "known_indels_sites_vcfs" : [ "gs://gcp-public-data--broad-references/hg38/v0/Mills_and_1000G_gold_standard.indels.hg38.vcf.gz", "gs://gcp-public-data--broad-references/hg38/v0/Homo_sapiens_assembly38.known_indels.vcf.gz" ],
         "reference_fasta" : {
           "ref_pac" : "gs://gcp-public-data--broad-references/hg38/v0/Homo_sapiens_assembly38.fasta.64.pac",
           "ref_bwt" : "gs://gcp-public-data--broad-references/hg38/v0/Homo_sapiens_assembly38.fasta.64.bwt",
           "ref_dict" : "gs://gcp-public-data--broad-references/hg38/v0/Homo_sapiens_assembly38.dict",
           "ref_ann" : "gs://gcp-public-data--broad-references/hg38/v0/Homo_sapiens_assembly38.fasta.64.ann",
           "ref_fasta_index" : "gs://gcp-public-data--broad-references/hg38/v0/Homo_sapiens_assembly38.fasta.fai",
           "ref_alt" : "gs://gcp-public-data--broad-references/hg38/v0/Homo_sapiens_assembly38.fasta.64.alt",
           "ref_fasta" : "gs://gcp-public-data--broad-references/hg38/v0/Homo_sapiens_assembly38.fasta",
           "ref_sa" : "gs://gcp-public-data--broad-references/hg38/v0/Homo_sapiens_assembly38.fasta.64.sa",
           "ref_amb" : "gs://gcp-public-data--broad-references/hg38/v0/Homo_sapiens_assembly38.fasta.64.amb"
         },
         "contamination_sites_ud" : "gs://gcp-public-data--broad-references/hg38/v0/contamination-resources/1000g/1000g.phase3.100k.b38.vcf.gz.dat.UD"
       },
       "cram_ref_fasta_index" : "gs://gcp-public-data--broad-references/hg38/v0/Homo_sapiens_assembly38.fasta.fai",
       "papi_settings" : {
         "agg_preemptible_tries" : 3,
         "preemptible_tries" : 3
       },
       "base_file_name" : "a.cram",
       "bait_interval_list" : "gs://gcp-public-data--broad-references/hg38/v0/HybSelOligos/whole_exome_illumina_coding_v1/whole_exome_illumina_coding_v1.Homo_sapiens_assembly38.baits.interval_list",
       "final_gvcf_base_name" : "a.cram",
       "target_interval_list" : "gs://gcp-public-data--broad-references/hg38/v0/HybSelOligos/whole_exome_illumina_coding_v1/whole_exome_illumina_coding_v1.Homo_sapiens_assembly38.targets.interval_list",
       "unmapped_bam_suffix" : ".unmapped.bam"
     }
   } ],
   "commit" : "commit-ish",
   "project" : "PO-1234",  
   "uuid" : "1337254e-f7d8-438d-a2b3-a74b199fee3c",  
   "wdl" : "pipelines/broad/reprocessing/external/exome/ExternalExomeReprocessing.wdl",
   "version" : "X.Y.Z"
 }]
```

The "workflows" field lists out each Cromwell workflow that was started, and
includes their status information. It is also possible to use the Job Manager
to check workflow progress and easily see information about any workflow
failures.

## A1 External Exome Workload-Request JSON Spec
```
{
     "pipeline": "string",
     "cromwell": "string",
     "output": "string",
     "project": "string",
     "shared_inputs": {
         "unmapped_bam_suffix":    "string",
         "cram_ref_fasta":       "string",
         "cram_ref_fasta_index": "string",
         "bait_set_name":        "string",
         "bait_interval_list":   "string",
         "target_interval_list": "string"
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
             "haplotype_scatter_count":     int,
             "break_bands_at_multiples_of": int
         },
         "papi_settings": {
             "agg_preemptible_tries": int,
             "preemptible_tries":     int
         },
         "fingerprint_genotypes_file": "string",
         "fingerprint_genotypes_index": "string",
     },
     "items": [
         {
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
             "target_interval_list": "string"
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
                 "haplotype_scatter_count":     int,
                 "break_bands_at_multiples_of": int
             },
             "papi_settings": {
                 "agg_preemptible_tries": int,
                 "preemptible_tries":     int
             },
             "fingerprint_genotypes_file": "string",
             "fingerprint_genotypes_index": "string",
             "destination_cloud_path": "string"
         }
      ]
}
```

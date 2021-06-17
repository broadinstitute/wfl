# Covid namespace

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

Although it is possible to be run differently, the typical life-cycle of a workload is a multi-stage process:

1. The caller needs to create a workload and specify the source, sink and executor. The source must be either a snapshot or a list of snapshots. If the former, then the type is `TDR Snapshots`. If the latter, than it is of type `TDRSnapshotListSource`. The executor must be of type `TerraExecutor`, and the sink must be of type `TerraWorkspaceSink`. If everything else passes verification, then the caller will receive a response which will contain the `uuid` of the workload.
   
2. Next, the caller needs to "start" the newly created workload, which will begin the analysis. 
   
3. WFL can, in addition, stop watching a workflow using the `stop` endpoint. This will not cancel analysis, but WFL will stop managing the workload.

To give more information, here are some example inputs to the above endpoints:

**GET /api/v1/workload**

==="Sample Request"

    ```
    curl 'http://localhost:8080/api/v1/workload' \
        -H 'Authorization: Bearer '$(gcloud auth print-access-token) \
        -H 'Accept: application/json'
    ```

=== "Sample Response"

    ```
    [ 
        {
            "watchers" : [ 
                "tester@123.com" 
            ],
            "labels" : [ 
                "hornet:test", "project:wfl-dev/CDC_Viral_Sequencing _ranthony_bashing_copy" 
            ],
            "creator" : "ranthony@broadinstitute.org",
            "created" : "2021-06-15T13:25:07Z",
            "source" : {
                "dataset" : "4bb51d98-b4aa-4c72-b76a-1a96a2ee3057",
                "table" : "flowcells",
                "column" : "last_modified_date",
                "snapshotReaders" : [ 
                    "workflow-launcher-dev@firecloud.org" 
                ],
                "name" : "Terra DataRepo"
            },
            "commit" : "73a852f36483818a9c89cc684448a360bc642fcd",
            "uuid" : "65b23fab-27af-4d47-8f88-46bb8e89df37",
            "executor" : {
                "workspace" : "wfl-dev/CDC_Viral_Sequencing _ranthony_bashing_copy",
                "methodConfiguration" : "wfl-dev/sarscov2_illumina_full",
                "methodConfigurationVersion" : 2,
                "fromSource" : "importSnapshot",
                "name" : "Terra"
            },
            "version" : "0.8.0",
            "sink" : {
                "workspace" : "wfl-dev/CDC_Viral_Sequencing _ranthony_bashing_copy",
                "entityType" : "flowcell",
                "fromOutputs" : {
                    "submission_xml" : "submission_xml",
                    "assembled_ids" : "assembled_ids",
                    "num_failed_assembly" : "num_failed_assembly",
                    "ivar_trim_stats_png" : "ivar_trim_stats_png",
                    "read_counts_raw" : "read_counts_raw",
                    "num_samples" : "num_samples",
                    "vadr_outputs" : "vadr_outputs",
                    "cleaned_reads_unaligned_bams" : "cleaned_reads_unaligned_bams",
                    "demux_commonBarcodes" : "demux_commonBarcodes",
                    "submission_zip" : "submission_zip",
                    "cleaned_bams_tiny" : "cleaned_bams_tiny",
                    "data_tables_out" : "data_tables_out",
                    "ntc_rejected_batches" : "ntc_rejected_batches",
                    "picard_metrics_alignment" : "picard_metrics_alignment",
                    "failed_assembly_ids" : "failed_assembly_ids",
                    "ivar_trim_stats_html" : "ivar_trim_stats_html",
                    "assembly_stats_tsv" : "assembly_stats_tsv",
                    "failed_annotation_ids" : "failed_annotation_ids",
                    "run_date" : "run_date",
                    "genbank_source_table" : "genbank_source_table",
                    "num_read_files" : "num_read_files",
                    "ntc_rejected_lanes" : "ntc_rejected_lanes",
                    "primer_trimmed_read_count" : "primer_trimmed_read_count",
                    "gisaid_fasta" : "gisaid_fasta",
                    "num_submittable" : "num_submittable",
                    "submit_ready" : "submit_ready",
                    "passing_fasta" : "passing_fasta",
                    "nextclade_auspice_json" : "nextclade_auspice_json",
                    "read_counts_depleted" : "read_counts_depleted",
                    "cleaned_bam_uris" : "cleaned_bam_uris",
                    "num_assembled" : "num_assembled",
                    "max_ntc_bases" : "max_ntc_bases",
                    "genbank_fasta" : "genbank_fasta",
                    "multiqc_report_cleaned" : "multiqc_report_cleaned",
                    "num_failed_annotation" : "num_failed_annotation",
                    "meta_by_filename_json" : "meta_by_filename_json",
                    "primer_trimmed_read_percent" : "primer_trimmed_read_percent",
                    "assembly_stats_final_tsv" : "assembly_stats_final_tsv",
                    "demux_metrics" : "demux_metrics",
                    "submittable_ids" : "submittable_ids",
                    "sra_metadata" : "sra_metadata",
                    "spikein_counts" : "spikein_counts",
                    "raw_reads_unaligned_bams" : "raw_reads_unaligned_bams",
                    "ivar_trim_stats_tsv" : "ivar_trim_stats_tsv",
                    "picard_metrics_wgs" : "picard_metrics_wgs",
                    "nextclade_all_json" : "nextclade_all_json",
                    "multiqc_report_raw" : "multiqc_report_raw",
                    "sequencing_reports" : "sequencing_reports",
                    "demux_outlierBarcodes" : "demux_outlierBarcodes",
                    "nextmeta_tsv" : "nextmeta_tsv",
                    "assemblies_fasta" : "assemblies_fasta"
                },
                "identifier" : "run_id",
                "name" : "Terra Workspace"
            }
        } 
    ]
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
                    "workspace": "wfl-dev/CDC_Viral_Sequencing _ranthony_bashing_copy",
                    "methodConfiguration": "wfl-dev/sarscov2_illumina_full",
                    "methodConfigurationVersion": 2,
                    "fromSource": "importSnapshot",
                    "name": "Terra"
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
                        "ivar_trim_stats_png" : "ivar_trim_stats_png",
                        "read_counts_raw" : "read_counts_raw",
                        "num_samples" : "num_samples",
                        "vadr_outputs" : "vadr_outputs",
                        "cleaned_reads_unaligned_bams" : "cleaned_reads_unaligned_bams",
                        "demux_commonBarcodes" : "demux_commonBarcodes",
                        "submission_zip" : "submission_zip",
                        "cleaned_bams_tiny" : "cleaned_bams_tiny",
                        "data_tables_out" : "data_tables_out",
                        "ntc_rejected_batches" : "ntc_rejected_batches",
                        "picard_metrics_alignment" : "picard_metrics_alignment",
                        "failed_assembly_ids" : "failed_assembly_ids",
                        "ivar_trim_stats_html" : "ivar_trim_stats_html",
                        "assembly_stats_tsv" : "assembly_stats_tsv",
                        "failed_annotation_ids" : "failed_annotation_ids",
                        "run_date" : "run_date",
                        "genbank_source_table" : "genbank_source_table",
                        "num_read_files" : "num_read_files",
                        "ntc_rejected_lanes" : "ntc_rejected_lanes",
                        "primer_trimmed_read_count" : "primer_trimmed_read_count",
                        "gisaid_fasta" : "gisaid_fasta",
                        "num_submittable" : "num_submittable",
                        "submit_ready" : "submit_ready",
                        "passing_fasta" : "passing_fasta",
                        "nextclade_auspice_json" : "nextclade_auspice_json",
                        "read_counts_depleted" : "read_counts_depleted",
                        "cleaned_bam_uris" : "cleaned_bam_uris",
                        "num_assembled" : "num_assembled",
                        "max_ntc_bases" : "max_ntc_bases",
                        "genbank_fasta" : "genbank_fasta",
                        "multiqc_report_cleaned" : "multiqc_report_cleaned",
                        "num_failed_annotation" : "num_failed_annotation",
                        "meta_by_filename_json" : "meta_by_filename_json",
                        "primer_trimmed_read_percent" : "primer_trimmed_read_percent",
                        "assembly_stats_final_tsv" : "assembly_stats_final_tsv",
                        "demux_metrics" : "demux_metrics",
                        "submittable_ids" : "submittable_ids",
                        "sra_metadata" : "sra_metadata",
                        "spikein_counts" : "spikein_counts",
                        "raw_reads_unaligned_bams" : "raw_reads_unaligned_bams",
                        "ivar_trim_stats_tsv" : "ivar_trim_stats_tsv",
                        "picard_metrics_wgs" : "picard_metrics_wgs",
                        "nextclade_all_json" : "nextclade_all_json",
                        "multiqc_report_raw" : "multiqc_report_raw",
                        "sequencing_reports" : "sequencing_reports",
                        "demux_outlierBarcodes" : "demux_outlierBarcodes",
                        "nextmeta_tsv" : "nextmeta_tsv",
                        "assemblies_fasta" : "assemblies_fasta"
                    }
                }
            }'
    ```

=== "Sample Response"

    ```
    {
        "watchers" : [ 
            "tester@123.com" 
        ],
        "labels" : [ 
            "hornet:test", 
            "project:wfl-dev/CDC_Viral_Sequencing _ranthony_bashing_copy" 
        ],
        "creator" : "ranthony@broadinstitute.org",
        "created" : "2021-06-15T18:50:52Z",
        "source" : {
            "dataset" : "4bb51d98-b4aa-4c72-b76a-1a96a2ee3057",
            "table" : "flowcells",
            "column" : "last_modified_date",
            "snapshotReaders" : [ 
                "workflow-launcher-dev@firecloud.org" 
            ],
            "name" : "Terra DataRepo"
        },
        "commit" : "73a852f36483818a9c89cc684448a360bc642fcd",
        "uuid" : "fb06bcf3-bc10-471b-a309-b2f99e4f5a67",
        "executor" : {
            "workspace" : "wfl-dev/CDC_Viral_Sequencing _ranthony_bashing_copy",
            "methodConfiguration" : "wfl-dev/sarscov2_illumina_full",
            "methodConfigurationVersion" : 2,
            "fromSource" : "importSnapshot",
            "name" : "Terra"
        },
        "version" : "0.8.0",
        "sink" : {
            "workspace" : "wfl-dev/CDC_Viral_Sequencing _ranthony_bashing_copy",
            "entityType" : "flowcell",
            "fromOutputs" : {
                ...
            },
            "identifier" : "run_id",
            "name" : "Terra Workspace"
        }
    }
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

=== "Sample Response"

    ```
    {
        "started" : "2021-06-15T18:54:57Z",
        "watchers" : [ 
            "tester@123.com" 
        ],
        "labels" : [ 
            "hornet:test", 
            "project:wfl-dev/CDC_Viral_Sequencing _ranthony_bashing_copy" 
        ],
        "creator" : "ranthony@broadinstitute.org",
        "updated" : "2021-06-15T18:54:57Z",
        "created" : "2021-06-15T18:50:52Z",
        "source" : {
            "dataset" : "4bb51d98-b4aa-4c72-b76a-1a96a2ee3057",
            "table" : "flowcells",
            "column" : "last_modified_date",
            "snapshotReaders" : [ 
                "workflow-launcher-dev@firecloud.org" 
            ],
            "name" : "Terra DataRepo"
        },
        "commit" : "73a852f36483818a9c89cc684448a360bc642fcd",
        "uuid" : "fb06bcf3-bc10-471b-a309-b2f99e4f5a67",
        "executor" : {
            "workspace" : "wfl-dev/CDC_Viral_Sequencing _ranthony_bashing_copy",
            "methodConfiguration" : "wfl-dev/sarscov2_illumina_full",
            "methodConfigurationVersion" : 2,
            "fromSource" : "importSnapshot",
            "name" : "Terra"
        },
        "version" : "0.8.0",
        "sink" : {
            "workspace" : "wfl-dev/CDC_Viral_Sequencing _ranthony_bashing_copy",
            "entityType" : "flowcell",
            "fromOutputs" : {
                ...
            },
            "identifier" : "run_id",
            "name" : "Terra Workspace"
            }
        }
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

=== "Sample Response"

    ```
    {
        "started" : "2021-06-15T18:54:57Z",
        "watchers" : [ 
            "tester@123.com" 
        ],
        "labels" : [ 
            "hornet:test", 
            "project:wfl-dev/CDC_Viral_Sequencing _ranthony_bashing_copy" 
        ],
        "creator" : "ranthony@broadinstitute.org",
        "updated" : "2021-06-15T18:59:28Z",
        "created" : "2021-06-15T18:50:52Z",
        "source" : {
            "dataset" : "4bb51d98-b4aa-4c72-b76a-1a96a2ee3057",
            "table" : "flowcells",
            "column" : "last_modified_date",
            "snapshotReaders" : [ 
                "workflow-launcher-dev@firecloud.org" 
            ],
            "name" : "Terra DataRepo"
        },
        "stopped" : "2021-06-15T18:59:28Z",
        "commit" : "73a852f36483818a9c89cc684448a360bc642fcd",
        "uuid" : "fb06bcf3-bc10-471b-a309-b2f99e4f5a67",
        "executor" : {
            "workspace" : "wfl-dev/CDC_Viral_Sequencing _ranthony_bashing_copy",
            "methodConfiguration" : "wfl-dev/sarscov2_illumina_full",
            "methodConfigurationVersion" : 2,
            "fromSource" : "importSnapshot",
            "name" : "Terra"
        },
        "version" : "0.8.0",
        "sink" : {
            "workspace" : "wfl-dev/CDC_Viral_Sequencing _ranthony_bashing_copy",
            "entityType" : "flowcell",
            "fromOutputs" : {
                ...
            },
            "identifier" : "run_id",
            "name" : "Terra Workspace"
            }
        }
    ```

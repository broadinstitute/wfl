# Arrays module

WorkFlow Launcher (WFL) implements `aou-arrays` module to
support secure and efficient processing of the AllOfUs Arrays
samples. This page documents the design principles and assumptions
of the module as well as summarizes the general process of module
development.

`aou-arrays` module implements arrays workload as a **continuous workload**, which
means all samples are coming in like a continuous stream, and WFL does not make
any assumption of how many samples will be in the workload or how to group the
samples together: it hands off the workload creation and starting process to its
caller.

### API

`aou-arrays` module, like others, implements the following _multimethod dispatchers_:

- start-workload!
- add-workload!

It supports the following API endpoints:

| Verb | Endpoint                            | Description                                                    |
|------|-------------------------------------|----------------------------------------------------------------|
| GET  | `/api/v1/workload`                  | List all workloads, optionally filtering by uuid or project    |
| GET  | `/api/v1/workload/{uuid}/workflows` | List all workflows for a specified workload uuid               |
| POST | `/api/v1/create`                    | Create a new workload                                          |
| POST | `/api/v1/start`                     | Start a workload                                               |
| POST | `/api/v1/stop`                      | Stop a running workload                                        |
| POST | `/api/v1/exec`                      | Create and start (execute) a workload                          |
| POST | `/api/v1/append_to_aou`             | Append a new or multiple sample(s) to an existing AOU workload |

Different from the fixed workload types that caller only needs to create a workload with a series of sample inputs and
then simply start the workload, `aou-arrays` module requires the caller to manage the life cycle of a workload on their
own in a multi-stage manner:

1. The caller needs to create a workload and specify the type to be `AllOfUsArrays`, the caller will receive the
information of the created workload if everything goes well, one of which is the `uuid` of the workload.
2. Once the workload information is being reviewed, the caller needs to "start" the newly created workload to
tell WFL that "this workload is ready to accept incoming samples". Without this "start" signal WFL will
refuse to append any sample to this workload.
3. The caller can append new individual samples to an existing started workload, and these new samples will be
analyzed, processed and submitted to Cromwell as long as it has valid information.

To give more information, here are some example inputs to the above endpoints:

**GET /api/v1/workload**

=== "Request"

    ```shell
    curl 'http://localhost:8080/api/v1/workload' \
        -H 'Authorization: Bearer '$(gcloud auth print-access-token) \
        -H 'Accept: application/json'
    ```

**GET /api/v1/workload?uuid={uuid}**

=== "Request"

    ```shell
    curl 'http://localhost:8080/api/v1/workload?uuid=00000000-0000-0000-0000-000000000000' \
        -H 'Authorization: Bearer '$(gcloud auth print-access-token) \
        -H 'Accept: application/json'
    ```

**GET /api/v1/workload?project={project}**

=== "Request"

    ```shell
    curl 'http://localhost:8080/api/v1/workload?project=(Test)%20WFL%20Local%20Testing' \
        -H 'Authorization: Bearer '$(gcloud auth print-access-token) \
        -H 'Accept: application/json'
    ```

!!! warning "Note"
    `project` and `uuid` are optional path parameters to the `/api/v1/workload` endpoint,
    hitting this endpoint without them will return all workloads. However, they cannot be specified
    together.

**GET /api/v1/workload/{uuid}/workflows**

=== "Request"

    ```shell
    curl 'http://localhost:8080/api/v1/workload/00000000-0000-0000-0000-000000000000/workflows' \
        -H 'Authorization: Bearer '$(gcloud auth print-access-token) 
    ```

**POST /api/v1/workload/create**

=== "Request"

    ```shell
    curl -X POST 'http://localhost:8080/api/v1/create' \
         -H 'Authorization: Bearer '$(gcloud auth print-access-token) \
         -H 'Accept: application/json' \
         -H 'Content-Type: application/json' \
         -d '{
               "executor": "https://cromwell-gotc-auth.gotc-dev.broadinstitute.org",
               "output":   "gs://broad-gotc-dev-wfl-ptc-test-outputs/aou-test-output/",
               "project":  "Example Project",
               "pipeline": "AllOfUsArrays"
             }'
    ```

**POST /api/v1/start**

=== "Request"

    ```shell
    curl -X POST 'http://localhost:8080/api/v1/start' \
         -H 'Authorization: Bearer '$(gcloud auth print-access-token) \
         -H 'Accept: application/json' \
         -H 'Content-Type: application/json' \
         -d $'{ "uuid": "00000000-0000-0000-0000-000000000000" }'
    ```

**POST /api/v1/workload/stop**

Stops the workload from accepting new inputs.
See also: `/api/v1/append_to_aou`.

=== "Request"

    ```shell
    curl -X POST 'http://localhost:8080/api/v1/stop' \
         -H 'Authorization: Bearer '$(gcloud auth print-access-token) \
         -H 'Accept: application/json' \
         -H 'Content-Type: application/json' \
         -d $'{ "uuid": "00000000-0000-0000-0000-000000000000" }'
    ```
    
**POST /api/v1/workload/append_to_aou**

=== "Request"

    ```shell
    curl -X POST 'http://localhost:8080/api/v1/append_to_aou' \
         -H 'Authorization: Bearer '$(gcloud auth print-access-token) \
         -H 'Accept: application/json' \
         -H 'Content-Type: application/json' \
         -d $'{
      "uuid": "00000000-0000-0000-0000-000000000000",
      "notifications": [
        {
          "zcall_thresholds_file": "foo",
          "sample_lsid": "foo",
          "bead_pool_manifest_file": "foo",
          "chip_well_barcode": "foo",
          "sample_alias": "foo",
          "green_idat_cloud_path": "foo",
          "red_idat_cloud_path": "foo",
          "cluster_file": "foo",
          "reported_gender": "foo",
          "gender_cluster_file": "foo",
          "params_file": "foo",
          "extended_chip_manifest_file": "foo",
          "analysis_version_number": 1
        },
        {
          "zcall_thresholds_file": "foo",
          "sample_lsid": "foo",
          "bead_pool_manifest_file": "foo",
          "chip_well_barcode": "foo",
          "sample_alias": "foo",
          "green_idat_cloud_path": "foo",
          "red_idat_cloud_path": "foo",
          "cluster_file": "foo",
          "reported_gender": "foo",
          "gender_cluster_file": "foo",
          "params_file": "foo",
          "extended_chip_manifest_file": "foo",
          "analysis_version_number": 5
        }
      ]
    }'
    ```

### Workload Model

WFL designed the following workload model in order to support the above API and workload submission mechanism.

Initially, it has the following schema:

```
                                    List of relations
 Schema |              Name              |   Type   |  Owner   |    Size    | Description
--------+--------------------------------+----------+----------+------------+-------------
 public | databasechangelog              | table    | foo      | 16 kB      |
 public | databasechangeloglock          | table    | foo      | 8192 bytes |
 public | workload                       | table    | foo      | 16 kB      |
 public | workload_id_seq                | sequence | foo      | 8192 bytes |
```

the `workload` table looks like:

```
 id | commit | created | creator | cromwell | finished | input | items | output | pipeline | project | release | started | uuid | version | wdl
----+--------+---------+---------+----------+----------+-------+-------+--------+----------+---------+---------+---------+------+---------+-----
(0 rows)
```

Note that different from the fixed workload types, `input`, `output` and `items` are not useful to `aou-arrays` workload
since these fields vary from sample to sample. Any information the caller provided to these fields will stored as
placeholders.

More importantly, even though `id` is the `primary` key here, `(pipeline, project, release)` works as the
unique identifier for arrays workloads, for instance, if there's already a workload with values:
`(AllOfUsArrays, gotc-dev, Arrays_v1.9)`, any further attempts to create a new workload with exact the same values
will return the information of this existing workload rather than create a new row.

Once the caller successfully creates a new sample, there will be a new row added to the above `workload` table, and a
new table will be created accordingly:

```
                                    List of relations
 Schema |              Name              |   Type   |  Owner   |    Size    | Description
--------+--------------------------------+----------+----------+------------+-------------
 public | allofusarrays_000000001        | table    | foo      | 16 kB      |
 public | allofusarrays_000000001_id_seq | sequence | foo      | 8192 bytes |
 public | databasechangelog              | table    | foo      | 16 kB      |
 public | databasechangeloglock          | table    | foo      | 8192 bytes |
 public | workload                       | table    | foo      | 16 kB      |
 public | workload_id_seq                | sequence | foo      | 8192 bytes |
```

The `allofusarrays_00000000X` table has the following fields:

```
 id | analysis_version_number | chip_well_barcode |  status   |            updated            |                 uuid
----+-------------------------+-------------------+-----------+-------------------------------+--------------------------------------
  1 |                       1 | 0000000000_R01C01 | Succeeded | 2020-07-21 00:00:00.241218-04 | 00000000-0000-0000-0000-000000000000
  2 |                       5 | 0000000000_R01C01 | Failed    | 2020-07-21 00:00:00.028976-04 | 00000000-0000-0000-0000-000000000000
```
Among which `(analysis_version_number, chip_well_barcode)` works as the `primary key`, any new samples that collide
with the existing `primary-keys` will be omitted.

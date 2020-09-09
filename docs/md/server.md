# WorkFlow Launcher Server

We now have the basics of WorkFlow Launcher
running as a server
in Google App Engine (GAE).

## Deploy to Google App Engine

To build and deploy WFL,
run `./ops/deploy.sh`.

It's Google Credentials page is here.

https://console.developers.google.com/apis/credentials?project=broad-gotc-dev

## WFL server features

The WFL server doesn't do much now.

- It can configure its secrets and deploy itself.
- It can authenticate to Google using OAuth2.
- It can serve authorized and unauthorized routes.

This is the application server's home URL.

https://dev-wfl.gotc-dev.broadinstitute.org/

## Create a workload

Defining a workload requires these top-level parameters.

| Parameter | Type       |
|-----------|------------ |
| project   | text       |
| cromwell  | URL        |
| pipeline  | pipeline   |
| input     | URL prefix |
| output    | URL prefix |

The parameters are used this way.

The `project` is just some text
to identify a researcher,
billing entity,
or cost object
responsible for the workload.

The `cromwell` URL specifies the Cromwell instance
to service the _workload_.

The `pipeline` enumeration implicitly identifies a data
schema for the inputs to and outputs from the workload.
You can think of it as the _kind_ of workflow
specified for the workload.
People sometimes refer to this as _the tag_
in that it is a well-known name
for a Cromwell pipeline defined in WDL.
You might also think of `pipeline`
as the external or official name
of a WFL processing module.

`ExternalWholeGenomeReprocessing`
is the only `pipeline` currently defined.
Pipelines differ in their processing and results,
so each `pipeline` requires a different
set of inputs for each workflow in a workload.

All input files for the workload
share the `input` URL prefix,
and all output files from the workload
share the `output` URL prefix.
These should be the longest common prefix
shared by all the files
to keep discovery and monitoring efficient.

## An ExternalWholeGenomeReprocessing workload

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

## URIs served

The following URIs work now.

 - Home ([/](https://dev-wfl.gotc-dev.broadinstitute.org/)) :
   Home replies with `Authorized!` when authorized.
   Otherwise it redirects to the Status page.

 - Status ([/status](https://dev-wfl.gotc-dev.broadinstitute.org/status)) :
   Status is an uauthorized endpoint that responds with "OK".

 - Version ([/version](https://dev-wfl.gotc-dev.broadinstitute.org/version)) :
   Version is an uauthorized endpoint that responds
   with the version currently deployed.

 - Environments
   ([/api/v1/environments](https://dev-wfl.gotc-dev.broadinstitute.org/api/v1/environments)) :
   Environments returns WFL's environment tree as JSON when authorized.
   Environments redirects to Status when unauthorized.

## Starting WFL server for local development

Run `./ops/server.sh` from the command line.

There is a `wrap-reload-for-development-only` handler wrapper
commented out on the `app` defined in the `server.clj` file.
When it is compiled in,
source code changes that you make
will be reloaded into the running server.

As its name implies,
you should comment it out
before deploying WFL.

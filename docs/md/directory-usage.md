# Using WFL Across a Directory

WFL supports starting workflows from a single file each--depending on the
pipeline you specify, other inputs will be extrapolated (see WFL's docs
for the specific pipeline for more information).

If you have a set of files uploaded to a GCS bucket and you'd like to start
a workflow for each one, you can do that via shell scripting.

!!! warning "Note"
    You will likely run into performance issues with WFL if you try to start
    hundreds or thousands of workflows in a single request to WFL. You'll need
    to split up the workflows into multiple workloads, tips for that are
    [here](#other-notes).

Suppose we have a set of CRAMs in a folder in some bucket, and we'd like to
submit them all to WFL for ExternalExomeReprocessing (perhaps associated with
some project or ticket, maybe PO-1234). We'll write a short bash script that
will handle this for us.

> Make sure you're able to list the files yourself! You'll need permissions
> and you may need to run `gcloud auth login`

## Step 1: List Files

We need a list of all the files you intend to process. This'll depend on the
file location, `gs://broad-gotc-dev-wfl-ptc-test-inputs/` for example. We can
use [wildcards](https://cloud.google.com/storage/docs/gsutil/addlhelp/WildcardNames)
to list out the individual files we'd like. Make some scratch file like
`script.sh` and store the list of CRAMs in a variable:

```bash
# In script.sh

CRAMS=$(gsutil ls 'gs://broad-gotc-dev-wfl-ptc-test-inputs/**.cram')
```

## Step 2: Format Items

First, we need to turn that string output into an actual list of file paths.
We can use `jq` to `split` into lines and `select` ones that are paths:

```bash
FILES=$(jq -sR 'split("\n") | map(select(startswith("gs://")))' <<< "$CRAMS")
```

Next, we need to format each of those file paths into inputs. WFL doesn't just
accept a list of files because we allow configuration of many other inputs and
options.

```bash
ITEMS=$(jq 'map({ inputs: { input_cram: .} })' <<< "$FILES")
```

!!! info
    If you want to process BAMs, you'll need to use `input_bam` instead of
    `input_cram` above.

## Step 3: Make Request

Now, we can simply insert those items into a normal ExternalExomeReprocessing
workload request:

```bash
REQUEST=$(jq '{
    cromwell: "https://cromwell-gotc-auth.gotc-prod.broadinstitute.org",
    output: "gs://broad-gotc-dev-wfl-ptc-test-outputs/xx-test-output",
    pipeline: "ExternalExomeReprocessing",
    project: "PO-1234",
    items: .
}' <<< "$ITEMS")
```

!!! info 
    Remember to change the `output` bucket! And the `project` isn't used by WFL
    but we keep track of it to help you organize workloads based on tickets
    or anything else.

!!! info 
    You can make other customizations here too, like specifying some input or
    option across all the workflows by adding a `common` block. See the docs
    for your pipeline or the [workflow options page](../workflow-options/) for
    more info.

Last, we can use `curl` to send off the request to WFL:

```bash
curl -X POST 'https://dev-wfl.gotc-dev.broadinstitute.org/api/v1/exec' \
    -H "Authorization: Bearer $(gcloud auth print-access-token)" \
    -H 'Content-Type: application/json' \
    -d "$REQUEST"
```

With this, the final result is something like the following:

```bash
CRAMS=$(gsutil ls 'gs://broad-gotc-dev-wfl-ptc-test-inputs/**.cram')

FILES=$(jq -sR 'split("\n") | map(select(startswith("gs://")))' <<< "$CRAMS")

ITEMS=$(jq 'map({ inputs: { input_cram: .} })' <<< "$FILES")

REQUEST=$(jq '{
    cromwell: "https://cromwell-gotc-auth.gotc-prod.broadinstitute.org",
    output: "gs://broad-gotc-dev-wfl-ptc-test-outputs/xx-test-output",
    pipeline: "ExternalExomeReprocessing",
    project: "PO-1234",
    items: .
}' <<< "$ITEMS")

curl -X POST 'https://dev-wfl.gotc-dev.broadinstitute.org/api/v1/exec' \
    -H "Authorization: Bearer $(gcloud auth print-access-token)" \
    -H 'Content-Type: application/json' \
    -d "$REQUEST"
```

Save that as `script.sh` and run with `bash myscript.sh` and you should be good
to go!

## Other Notes
Have a lot of workflows to submit? You can use array slicing to help split
things up:

```bash
FILES=$(jq -sR 'split("\n") | map(select(startswith("gs://")))[0:50]' <<< "$CRAMS")
```

Need to select files matching some other query too? You can chain the
`map`-`select` commands and use other string filters on the file names:

```bash
FILES=$(jq -sR 'split("\n") | map(select(startswith("gs://"))) |
    map(select(contains("foobar")))' <<< "$CRAMS")
```

If `contains`/`startswith`/`endswith` aren't enough, you can use `test`
with PCRE regex:

```bash
FILES=$(jq -sR 'split("\n") | map(select(startswith("gs://"))) |
    map(select(test("fo+bar")))' <<< "$CRAMS")
```

See [this page for more `jq` info](https://stedolan.github.io/jq/manual/).

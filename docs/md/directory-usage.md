# Using WFL Across a Directory

Both the [WGS](../modules-wgs/) and [XX](../modules-external-exome-reprocessing/) 
modules support starting workflows from a single file each--the modules are
capable of extrapolating the other required inputs.

If you have a set of files uploaded to a GCS bucket and you'd like to start
a workflow for each one, you can do that via shell scripting.

!!! warning "Note"
    You will likely run into performance issues with WFL if you try to start
    hundreds or thousands of workflows in a single request to WFL. You'll need
    to split up the workflows into multiple workloads, tips for that are
    [here](#other-notes).

## Step 1: List Files

The easy part first: you'll need to get a list of the files ready for piping.
Assuming they're in a bucket, you can use 
[GCS wildcards](https://cloud.google.com/storage/docs/gsutil/addlhelp/WildcardNames):

```bash
gsutil ls 'gs://broad-gotc-dev-wfl-ptc-test-inputs/**.cram'
```

## Step 2: Format Into a Request

You can use `jq -sR` (`jq --slurp --raw-input`) to form the list of files into
a request.

The basic idea:

1. Split the slurped raw input into a list of files
2. Format that list of files into the `items` for the request
3. Slot the `items` into a full request

For the first part, we can use `split` to split on newlines and `map` and `select`
to cut out any gsutil cruft (update notifications, blank lines, etc):

```bash
gsutil ls 'gs://broad-gotc-dev-wfl-ptc-test-inputs/**.cram' \
    | jq -sR 'split("\n") | map(select(startswith("gs://")))'
```

For the second part, we can use `map` to format each filepath into a proper
block (with inputs, any custom options, etc):

```bash
gsutil ls 'gs://broad-gotc-dev-wfl-ptc-test-inputs/**.cram' \
    | jq -sR 'split("\n") | map(select(startswith("gs://"))) |
        map({ inputs: { input_cram: .} })'
```

Finally, we can insert that into an otherwise normal request:

```bash
gsutil ls 'gs://broad-gotc-dev-wfl-ptc-test-inputs/**.cram' \
    | jq -sR 'split("\n") | map(select(startswith("gs://"))) |
        map({ inputs: { input_cram: .} }) |
        {
            cromwell: "https://cromwell-gotc-auth.gotc-dev.broadinstitute.org",
            output: "gs://broad-gotc-dev-wfl-ptc-test-outputs/xx-test-output",
            pipeline: "ExternalExomeReprocessing",
            project: "Example Project",
            items: .
        }'
```

## Step 3: Send Request

We can pipe what we've got to `curl` to send it off:

```bash
gsutil ls 'gs://broad-gotc-dev-wfl-ptc-test-inputs/**.cram' \
    | jq -sR 'split("\n") | map(select(startswith("gs://"))) |
        map({ inputs: { input_cram: .} }) |
        {
            cromwell: "https://cromwell-gotc-auth.gotc-dev.broadinstitute.org",
            output: "gs://broad-gotc-dev-wfl-ptc-test-outputs/xx-test-output",
            pipeline: "ExternalExomeReprocessing",
            project: "Example Project",
            items: .
        }' \
    | curl -X POST 'https://dev-wfl.gotc-dev.broadinstitute.org/api/v1/exec' \
        -H "Authorization: Bearer $(gcloud auth print-access-token)" \
        -H 'Content-Type: application/json' \
        -d @-
```


## Other Notes
Have a lot of workflows to submit? You can use array slicing to help split
things up to quickly submit multiple workloads. 

```bash
gsutil ls 'gs://broad-gotc-dev-wfl-ptc-test-inputs/**.cram' \
    | jq -sR 'split("\n") | map(select(startswith("gs://")))[0:50]'
        # ...
```

Need to select files matching some other query too? You can chain the
`map`-`select` commands and use other string filters:

```bash
gsutil ls 'gs://broad-gotc-dev-wfl-ptc-test-inputs/**.cram' \
    | jq -sR 'split("\n") | map(select(startswith("gs://"))) |
        map(select(contains("foobar")))'
        # ...
```

If `contains`/`startswith`/`endswith` aren't enough, you can use `test`
with PCRE regex:

```bash
gsutil ls 'gs://broad-gotc-dev-wfl-ptc-test-inputs/**.cram' \
    | jq -sR 'split("\n") | map(select(startswith("gs://"))) |
        map(select(test("fo+bar")))'
        # ...
```

See [this page for more `jq` info](https://stedolan.github.io/jq/manual/).

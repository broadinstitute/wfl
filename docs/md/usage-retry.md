# Retrying Workflows

## Retrying Terra Workflows via WFL API

WFL has a `/retry` endpoint that selects workflows by their completion status
and re-submits them.

The following `curl` shell command finds the workflows
with the Cromwell status `"Failed"` in workload `$UUID`
and resubmits them for processing.

```bash
WFL=https://gotc-prod-wfl.gotc-prod.broadinstitute.org/api/v1/workload
AUTH="Authorization: Bearer $(gcloud auth print-access-token)"
UUID=0d307eb3-2b8e-419c-b687-8c08c84e2a0c # workload UUID

curl -X POST -H "$AUTH" $WFL/$UUID/retry --data '{"status":"Failed"}' | jq
```

A successful `/retry` request returns the workload specified by `$UUID`.
A failed `/retry` request will return a description of the failure.

The `/retry` endpoint is not yet implemented for all workloads
and in such cases returns a `501` HTTP failure status.
[Staged workloads](./staged-workload.md) with a [Terra executor](./executor.md#terra-executor)
are likely to implement this endpoint.
For confirmation, see module-specific documentation.
For unimplemented cases, see the [runbook](#retrying-failures-via-wfl-runbook) below.

### Supported Statuses

The only
[Cromwell statuses](https://github.com/broadinstitute/wfl/blob/6bcfde01542bfef1601eaaf4bb2657cf1520218f/api/src/wfl/service/cromwell.clj#L12-L14)
supported with the `/retry` API
are the terminal workflow statuses:

- `"Aborted"`

- `"Failed"`

- `"Succeeded"`

???+ info "Why would you retry succeeded workflows?"
    A workflow may have functionally succeeded, but be scientifically inaccurate
    and need to be rerun, e.g. if the initial run contained incorrect metadata.

Attempting to retry workflows of any other status
will return a `400` HTTP failure status,
as will specifying a status for which the workload has no workflows.

### Warnings and Caveats

#### Submission of snapshot subsets not yet supported

WFL is limited by [Rawls](https://github.com/broadinstitute/rawls) functionality
and cannot yet submit a subset of a snapshot.
So retrying any workflow from a workload snapshot
will resubmit all entities from that snapshot.

Example - a running submission from a snapshot has 500 workflows:

- 1 failed

- 249 running

- 250 succeeded

Retrying the failed workflow will create a new submission
where all 500 original workflows are retried.

Consider whether you should wait for all workflows in the submission to complete
before initiating a retry
to avoid multiple workflows running concurrently
in competition for the same output files.

#### Race condition when retrying the same workload concurrently

A caller could hit this endpoint for the same workload multiple times in quick succession,
making possible a race condition where each run retries the same set of workflows.

Future improvements will make this operation threadsafe, but in the interim
try to wait for a response from your retry request before resubmitting.

## Retrying Failures via WFL Runbook

WFL remembers enough about submissions to let you quickly resubmit failed
workflows with the same inputs/options as they were originally submitted.

All you need is a query string like you'd pass to the `/workflows` endpoint,
either:

- `uuid=<UUID>` where `<UUID>` is the identifier of the specific workload you'd
like to retry failures from
    - Ex: `uuid=95d536c7-ce3e-4ffc-8c9c-2b9c710d625a`
- `project=<PROJECT>` where `<PROJECT>` is the value of the project field of the
workloads you'd like to retry
    - Ex: `project=PO-29619`

With the below script, WFL will find matching workloads and resubmit any unique
failures of individual workflows in a new workload (with the same parameters as
the originals).

Usage: `bash retry.sh QUERY`

Ex: `bash retry.sh project=PO-29619`

```bash
# Usage: bash abort.sh QUERY [WFL_URL]
#   QUERY is either like `project=PO-123` or `uuid=1a2b3c4d`
#   WFL_URL is the WFL instance to abort workflows from [default: gotc-prod]

WFL_URL="${2:-https://gotc-prod-wfl.gotc-prod.broadinstitute.org}"
AUTH_HEADER="Authorization: Bearer $(gcloud auth print-access-token)"

getWorkloads () {
    # Query -> [Workload]
    curl -s -X GET "${WFL_URL}/api/v1/workload?$1" \
         -H "${AUTH_HEADER}" \
         | jq
}

getWorkflows() {
    # Workload -> [Workflow]
    uuid=$(jq -r .uuid <<< "$1")
    curl -s -X GET "${WFL_URL}/api/v1/workload/${uuid}/workflows" \
         -H "${AUTH_HEADER}" \
         | jq
}

removeSucceeded() {
    # [[Workflow]] -> [Workflow]
    jq 'flatten
        | map ( select ( .status=="Failed" )
                | {inputs: .inputs, options: .options}
                | del ( .[] | nulls ) )
        ' <<< "$1"
}

makeRetryRequest() {
    # [Workload], [Workflow] -> Request
    jq --argjson 'workflows' "$2" \
        '.[0]
         | { executor: .executor
           , input: .input
           , output: .output
           , pipeline: .pipeline
           , project: .project
           , items: $workflows
           }
        | del(.[] | nulls)
        ' <<< "$1"
}

mapjq () {
    jq -c '.[]' <<< "${2}" \
    | while read elem; do ${1} "${elem}"; done \
    | jq -s '[ .[] ]'
}

main() {
    # Query -> ()
    workloads=$(getWorkloads "${1}")
    workflows=$(mapjq getWorkflows "${workloads}")
    toSubmit=$(removeSucceeded "${workflows}")
    makeRetryRequest "${workloads[0]}" "${toSubmit}" > /tmp/retry.json

    curl -X POST "${WFL_URL}/api/v1/exec" \
         -H "${AUTH_HEADER}" \
         -H "Content-Type: application/json" \
         -d @/tmp/retry.json
}

main "$1"
```

### Tips

#### Customizing Inputs/Options

If you want to inject a new input or option into all of the retried workflows,
you can do that with a `common` block. For example, replace this:

```
jq '{
    executor: .executor,
```

with this:

```
jq '{
    common: { inputs: { "WholeGenomeReprocessing.WholeGenomeGermlineSingleSample.BamToCram.ValidateCram.memory_multiplier": 2 } },
    executor: .executor,
```

That example uses WFL's arbitrary input feature to bump up the memory multiplier
for a particular WGS task.

- Nested inputs will have periods in them, you'll need to use quotes around it
- You can't override inputs or options that the workflows originally had (the
`common` block has lower precedence)

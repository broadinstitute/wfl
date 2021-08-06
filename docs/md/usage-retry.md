# Retry Workflows in Terra

WFL has a `/retry` endpoint
that selects workflows
by their completion status
and re-submits them.

The following `curl` shell command
finds the workflows
with the Cromwell status `"Failed"`
in the workload with `$UUID`
and resubmits them for processing.

```bash
WFL=https://gotc-prod-wfl.gotc-prod.broadinstitute.org/api/v1/workload
AUTH="Authorization: Bearer $(gcloud auth print-access-token)"
UUID=0d307eb3-2b8e-419c-b687-8c08c84e2a0c # workload UUID

curl -X POST -H "$AUTH" $WFL/$UUID/retry --data '{"status":"Failed"}' | jq
```

The only
[Cromwell statuses](https://github.com/broadinstitute/wfl/blob/c87ce58e815c9fe73648471057c8d3cda7bc02e0/api/src/wfl/service/cromwell.clj#L12-L14)
supported with the `/retry` API
are the terminal and likely failure workflow statuses:

- `"Aborted"`

- `"Failed"`

Attempting to retry workflows of any other status
will return a `400` HTTP failure status,
as will specifying a status for which the
workload has no workflows.

!!! warning "Submission of snapshot subsets not yet supported"
    Note that WFL is limited by Rawls functionality
    and cannot yet submit a subset of a snapshot.
    So retrying any workflow from a workload snapshot
    will resubmit all entities from that snapshot.

    Example - a running submission from a snapshot
    has 500 workflows:

    - 1 failed

    - 249 running

    - 250 succeeded

    Retrying the failed workflow will create a new submission
    where all 500 original workflows are retried.

    Consider whether you should wait for all workflows
    in the submission to complete before initiating a retry
    to avoid multiple workflows running concurrently
    in competition for the same output files.

A successful `/retry` request
returns the workload
specified by `$UUID`.
A failed `/retry` request
will return a description
of the failure.

The `/retry` endpoint is not yet implemented for all workflows
and in such cases returns a `501` HTTP failure status.
Until it is supported,
see the method below.

# Retrying Failures via WFL

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
        | group_by( {inputs: .inputs, options: .options} )
        | map( select( all(.status=="Succeeded") )
             | .[0]
             | {inputs: .inputs, options: .options} )
        | del(.[][] | nulls)
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

## Tips

### Customizing Inputs/Options

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
